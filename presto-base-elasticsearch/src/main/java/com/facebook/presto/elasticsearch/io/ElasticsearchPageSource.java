package com.facebook.presto.elasticsearch.io;

import com.facebook.presto.elasticsearch.BaseClient;
import com.facebook.presto.elasticsearch.model.ElasticsearchColumnHandle;
import com.facebook.presto.elasticsearch.model.ElasticsearchSplit;
import com.facebook.presto.spi.ConnectorPageSource;
import com.facebook.presto.spi.Page;
import com.facebook.presto.spi.PageBuilder;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.block.BlockBuilder;
import com.facebook.presto.spi.type.StandardTypes;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.spi.type.TypeSignatureParameter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import io.airlift.slice.Slice;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.facebook.presto.elasticsearch.ElasticsearchErrorCode.UNEXPECTED_ES_ERROR;
import static com.facebook.presto.elasticsearch.Types.isArrayType;
import static com.facebook.presto.elasticsearch.Types.isMapType;
import static com.facebook.presto.elasticsearch.Types.isRowType;
import static com.facebook.presto.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.DateType.DATE;
import static com.facebook.presto.spi.type.IntegerType.INTEGER;
import static com.facebook.presto.spi.type.TimeType.TIME;
import static com.facebook.presto.spi.type.TimestampType.TIMESTAMP;
import static com.facebook.presto.spi.type.VarbinaryType.VARBINARY;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.util.stream.Collectors.toList;

public class ElasticsearchPageSource
        implements ConnectorPageSource
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int ROWS_PER_REQUEST = 1024;

    private final List<String> columnNames;
    private final List<Type> columnTypes;

    private long count;
    private boolean finished;
    private final SearchResult<Map<String, Object>> searchResult;

    private PageBuilder pageBuilder;

    public ElasticsearchPageSource(
            BaseClient elasticsearchClient,
            ElasticsearchSplit split,
            List<ElasticsearchColumnHandle> columns)
    {
        this.columnNames = columns.stream().map(ElasticsearchColumnHandle::getName).collect(toList());
        this.columnTypes = columns.stream().map(ElasticsearchColumnHandle::getType).collect(toList());

        //--exec query
        this.searchResult = elasticsearchClient.execute(split, columns);
        pageBuilder = new PageBuilder(columnTypes);
    }

    @Override
    public long getCompletedBytes()
    {
        return count;
    }

    @Override
    public long getReadTimeNanos()
    {
        return 0;
    }

    @Override
    public boolean isFinished()
    {
        return finished;
    }

    /**
     * es array字段 展平逻辑
     * 目前只能展平一个字段 如果有多个字段则存在 如何join的问题
     */
    private class ArrayDocForeachFunc
    {
        private final int rowlisteMapColumn;
        private final List<Map> rowlisteMap;
        private final Map<String, Object> docMap;

        private ArrayDocForeachFunc(int rowlisteMapColumn, Map<String, Object> docMap)
        {
            this.rowlisteMapColumn = rowlisteMapColumn;
            this.docMap = docMap;
            this.rowlisteMap = (List<Map>) docMap.get(columnNames.get(rowlisteMapColumn));
        }

        void accept()
        {
            for (int i = 1; i < rowlisteMap.size(); i++) {
                pageBuilder.declarePosition();
                for (int column = 0; column < columnTypes.size(); column++) {
                    BlockBuilder output = pageBuilder.getBlockBuilder(column);
                    Type type = columnTypes.get(column);
                    Object value = docMap.get(columnNames.get(column));
                    if (column == rowlisteMapColumn) {
                        appendTo(type, rowlisteMap.get(i), output);
                    }
                    else {
                        appendTo(type, value, output);
                    }
                }
            }
        }
    }

    @Override
    public Page getNextPage()
    {
        verify(pageBuilder.isEmpty());
        count = 0;
        for (int i = 0; i < ROWS_PER_REQUEST; i++) {
            if (!searchResult.hasNext()) {
                finished = true;
                break;
            }
            Map<String, Object> docMap = searchResult.next();
            count++;

            pageBuilder.declarePosition();
            ImmutableList.Builder<ArrayDocForeachFunc> funcs = ImmutableList.builder();
            for (int column = 0; column < columnTypes.size(); column++) {
                BlockBuilder output = pageBuilder.getBlockBuilder(column);
                Type type = columnTypes.get(column);
                Object value = docMap.get(columnNames.get(column));
                //如果是 array[] 字段 则下面会进行打平处理 es head展示时则只显示了array第一个元素
                if (isRowType(type) && value instanceof List && ((List) value).size() > 0 && ((List) value).get(0) instanceof Map) {
                    final List<Map> listSourceMap = (List<Map>) value;
                    appendTo(type, listSourceMap.get(0), output);
                    //----下面是展平逻辑 目前存在如果有多个array字段 展平时笛卡尔积join问题---
                    // 此处不做展平处理, 逻辑将和<es head `基本查询`>保持一致
                    //funcs.add(new ArrayDocForeachFunc(column, docMap));
                }
                else {
                    appendTo(type, value, output);
                }
            }
            funcs.build().forEach(func -> func.accept());
        }

        Page page = pageBuilder.build();
        pageBuilder.reset();
        return page;
    }

    @Override
    public long getSystemMemoryUsage()
    {
        return 0;
    }

    @Override
    public void close()
            throws IOException
    {
        searchResult.close();
    }

    private void appendTo(Type type, Object value, BlockBuilder output)
    {
        if (value == null) {
            output.appendNull();
            return;
        }

        Class<?> javaType = type.getJavaType();
        try {
            if (javaType == boolean.class) {
                type.writeBoolean(output, (Boolean) value);
            }
            else if (javaType == long.class) {
                if (type.equals(BIGINT)) {
                    type.writeLong(output, ((Number) value).longValue());
                }
                else if (type.equals(INTEGER)) {
                    type.writeLong(output, ((Number) value).intValue());
                }
                else if (type.equals(DATE)) {
                    long utcMillis = ((Date) value).getTime();
                    type.writeLong(output, TimeUnit.MILLISECONDS.toDays(utcMillis));
                }
                else if (type.equals(TIME)) {
                    type.writeLong(output, ((Date) value).getTime());
                }
                else if (type.equals(TIMESTAMP)) {
                    type.writeLong(output, ((Date) value).getTime());
                }
                else {
                    throw new PrestoException(GENERIC_INTERNAL_ERROR, "Unhandled type for " + javaType.getSimpleName() + ":" + type.getTypeSignature());
                }
            }
            else if (javaType == double.class) {
                type.writeDouble(output, ((Number) value).doubleValue());
            }
            else if (javaType == Slice.class) {
                writeSlice(output, type, value);
            }
            else if (javaType == Block.class) {
                writeBlock(output, type, value);
            }
            else {
                throw new PrestoException(GENERIC_INTERNAL_ERROR, "Unhandled type for " + javaType.getSimpleName() + ":" + type.getTypeSignature());
            }
        }
        catch (ClassCastException ignore) {
            // returns null instead of raising exception
            output.appendNull();
        }
    }

    private String toVarcharValue(Object value)
    {
        if (value instanceof Collection<?>) {
            return "[" + String.join(", ", ((Collection<?>) value).stream().map(this::toVarcharValue).collect(toList())) + "]";
        }
        if (value instanceof Map) {
            try {
                return MAPPER.writeValueAsString(value);
            }
            catch (JsonProcessingException e) {
                throw new PrestoException(UNEXPECTED_ES_ERROR, e);
            }
        }
        return String.valueOf(value);
    }

    private void writeSlice(BlockBuilder output, Type type, Object value)
    {
        String base = type.getTypeSignature().getBase();
        if (base.equals(StandardTypes.VARCHAR)) {
            type.writeSlice(output, utf8Slice(toVarcharValue(value)));
        }
        else if (type.equals(VARBINARY)) {
            if (value instanceof byte[]) {
                type.writeSlice(output, wrappedBuffer(((byte[]) value)));
            }
            else {
                output.appendNull();
            }
        }
        else {
            throw new PrestoException(GENERIC_INTERNAL_ERROR, "Unhandled type for Slice: " + type.getTypeSignature());
        }
    }

    private void writeBlock(BlockBuilder output, Type type, Object value)
    {
        if (isArrayType(type)) {
            if (value instanceof List<?>) {
                BlockBuilder builder = output.beginBlockEntry();

                ((List<?>) value).forEach(element ->
                        appendTo(type.getTypeParameters().get(0), element, builder));

                output.closeEntry();
                return;
            }
        }
        else if (isMapType(type)) {
            if (value instanceof List<?>) {
                BlockBuilder builder = output.beginBlockEntry();
                for (Object element : (List<?>) value) {
                    if (!(element instanceof Map<?, ?>)) {
                        continue;
                    }

                    Map<?, ?> document = (Map<?, ?>) element;
                    if (document.containsKey("key") && document.containsKey("value")) {
                        appendTo(type.getTypeParameters().get(0), document.get("key"), builder);
                        appendTo(type.getTypeParameters().get(1), document.get("value"), builder);
                    }
                }

                output.closeEntry();
                return;
            }
            else if (value instanceof Map) {
                BlockBuilder builder = output.beginBlockEntry();
                Map<?, ?> document = (Map<?, ?>) value;
                for (Map.Entry<?, ?> entry : document.entrySet()) {
                    appendTo(type.getTypeParameters().get(0), entry.getKey(), builder);
                    appendTo(type.getTypeParameters().get(1), entry.getValue(), builder);
                }
                output.closeEntry();
                return;
            }
        }
        else if (isRowType(type)) {
            if (value instanceof Map) {
                Map<?, ?> mapValue = (Map<?, ?>) value;
                BlockBuilder builder = output.beginBlockEntry();

                List<String> fieldNames = new ArrayList<>();
                for (int i = 0; i < type.getTypeSignature().getParameters().size(); i++) {
                    TypeSignatureParameter parameter = type.getTypeSignature().getParameters().get(i);
                    fieldNames.add(parameter.getNamedTypeSignature().getName().orElse("field" + i));
                }
                checkState(fieldNames.size() == type.getTypeParameters().size(), "fieldName doesn't match with type size : %s", type);
                for (int index = 0; index < type.getTypeParameters().size(); index++) {
                    appendTo(type.getTypeParameters().get(index), mapValue.get(fieldNames.get(index)), builder);
                }
                output.closeEntry();
                return;
            }
            else if (value instanceof List<?>) {
                List<?> listValue = (List<?>) value;
                BlockBuilder builder = output.beginBlockEntry();
                for (int index = 0; index < type.getTypeParameters().size(); index++) {
                    if (index < listValue.size()) {
                        appendTo(type.getTypeParameters().get(index), listValue.get(index), builder);
                    }
                    else {
                        builder.appendNull();
                    }
                }
                output.closeEntry();
                return;
            }
        }
        else {
            throw new PrestoException(GENERIC_INTERNAL_ERROR, "Unhandled type for Block: " + type.getTypeSignature());
        }

        // not a convertible value
        output.appendNull();
    }
}
