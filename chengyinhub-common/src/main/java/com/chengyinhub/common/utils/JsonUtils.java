package com.chengyinhub.common.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JSON 工具类
 * 基于 Jackson 实现
 */
public class JsonUtils {

    private static final Logger log = LoggerFactory.getLogger(JsonUtils.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    static {
        // 配置 ObjectMapper
        // 忽略未知属性
        OBJECT_MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // 空对象不抛出异常
        OBJECT_MAPPER.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        // 支持 Java 8 时间类型
        OBJECT_MAPPER.registerModule(new JavaTimeModule());
        // 禁用将日期写为时间戳
        OBJECT_MAPPER.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }

    /**
     * 获取 ObjectMapper 实例
     */
    public static ObjectMapper getObjectMapper() {
        return OBJECT_MAPPER;
    }

    /**
     * 将对象转换为 JSON 字符串
     *
     * @param object 要转换的对象
     * @return JSON 字符串，如果转换失败返回 null
     */
    public static String toJsonString(Object object) {
        if (object == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            log.error("对象转 JSON 字符串失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 将对象转换为 JSON 字符串（格式化输出）
     *
     * @param object 要转换的对象
     * @return 格式化后的 JSON 字符串，如果转换失败返回 null
     */
    public static String toPrettyJsonString(Object object) {
        if (object == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(object);
        } catch (JsonProcessingException e) {
            log.error("对象转格式化 JSON 字符串失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 将 JSON 字符串转换为指定类型的对象
     *
     * @param json  JSON 字符串
     * @param clazz 目标类型
     * @param <T>   泛型
     * @return 转换后的对象，如果转换失败返回 null
     */
    public static <T> T parseObject(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, clazz);
        } catch (IOException e) {
            log.error("JSON 字符串转对象失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 将 JSON 字符串转换为指定类型的对象（支持复杂泛型）
     *
     * @param json          JSON 字符串
     * @param typeReference 类型引用
     * @param <T>           泛型
     * @return 转换后的对象，如果转换失败返回 null
     */
    public static <T> T parseObject(String json, TypeReference<T> typeReference) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, typeReference);
        } catch (IOException e) {
            log.error("JSON 字符串转对象失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 将 JSON 字符串转换为 Map
     *
     * @param json JSON 字符串
     * @return Map 对象，如果转换失败返回 null
     */
    public static Map<String, Object> parseToMap(String json) {
        return parseObject(json, new TypeReference<Map<String, Object>>() {});
    }

    /**
     * 将 JSON 字符串转换为 List
     *
     * @param json JSON 字符串
     * @param clazz List 中元素的类型
     * @param <T> 泛型
     * @return List 对象，如果转换失败返回空 List
     */
    public static <T> List<T> parseToList(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            JavaType javaType = OBJECT_MAPPER.getTypeFactory().constructCollectionType(List.class, clazz);
            return OBJECT_MAPPER.readValue(json, javaType);
        } catch (IOException e) {
            log.error("JSON 字符串转 List 失败: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    /**
     * 将对象转换为 Map
     *
     * @param object 要转换的对象
     * @return Map 对象，如果转换失败返回 null
     */
    public static Map<String, Object> objectToMap(Object object) {
        if (object == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.convertValue(object, new TypeReference<Map<String, Object>>() {});
        } catch (IllegalArgumentException e) {
            log.error("对象转 Map 失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 将 Map 转换为指定类型的对象
     *
     * @param map   Map 对象
     * @param clazz 目标类型
     * @param <T>   泛型
     * @return 转换后的对象，如果转换失败返回 null
     */
    public static <T> T mapToObject(Map<String, Object> map, Class<T> clazz) {
        if (map == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.convertValue(map, clazz);
        } catch (IllegalArgumentException e) {
            log.error("Map 转对象失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 判断字符串是否为有效的 JSON 格式
     *
     * @param json 要判断的字符串
     * @return true：是有效的 JSON；false：不是有效的 JSON
     */
    public static boolean isValidJson(String json) {
        if (json == null || json.isEmpty()) {
            return false;
        }
        try {
            OBJECT_MAPPER.readTree(json);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 格式化 JSON 字符串
     *
     * @param json 未格式化的 JSON 字符串
     * @return 格式化后的 JSON 字符串，如果格式化失败返回原字符串
     */
    public static String formatJson(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        try {
            Object obj = OBJECT_MAPPER.readValue(json, Object.class);
            return toPrettyJsonString(obj);
        } catch (IOException e) {
            log.warn("JSON 格式化失败: {}", e.getMessage());
            return json;
        }
    }

    /**
     * 从 JSON 字符串中提取指定路径的值（简单实现，复杂路径建议使用 JsonPath）
     *
     * @param json JSON 字符串
     * @param key  要提取的键
     * @return 提取到的值，如果不存在返回 null
     */
    public static Object extractValue(String json, String key) {
        Map<String, Object> map = parseToMap(json);
        if (map != null) {
            return map.get(key);
        }
        return null;
    }
}