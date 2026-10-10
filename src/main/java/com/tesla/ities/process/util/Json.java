package com.tesla.ities.process.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tesla.ities.process.exception.ProcessException;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** JSON <-> Map/List 工具。config/params/result/listener_chain 以 JSON 文本落库, 用本类转换(不用 DB 原生 JSON 类型)。 */
public final class Json {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {};
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE =
            new TypeReference<List<Map<String, Object>>>() {};

    private Json() {}

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String write(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new ProcessException("AU204701", e);
        }
    }

    /**
     * 反序列化为指定类型对象(定义快照借出用: 缓存里的 JSON 快照每次读出一个全新的对象图)。
     * 失败一律抛 ProcessException(与 {@link #readMap} 同口径, 不静默吞成 null/空对象)。
     */
    public static <T> T read(String json, Class<T> type) {
        if (json == null || json.isEmpty()) {
            throw new ProcessException("AU204702", type.getSimpleName());
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new ProcessException("AU204703", e, type.getSimpleName());
        }
    }

    public static Map<String, Object> readMap(String json) {
        if (json == null || json.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return MAPPER.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            throw new ProcessException("AU204704", e, json);
        }
    }

    /** 反序列化为 List<Map>(如 listener_chain)。空/异常返回空列表。 */
    public static List<Map<String, Object>> readListMap(String json) {
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return MAPPER.readValue(json, LIST_MAP_TYPE);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
