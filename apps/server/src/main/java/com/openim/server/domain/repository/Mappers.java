package com.openim.server.domain.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Repository 层共用的类型转换工具
 *
 * 领域模型对外使用 String 表示 ID、Instant 表示时间、Map 表示 jsonb 负载，
 * 数据库使用 uuid / timestamptz / jsonb。转换统一收敛在这里，避免各 Repository 重复实现。
 */
final class Mappers {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private Mappers() {
    }

    /** 读取可空的 timestamptz */
    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** 把外部传入的字符串 ID 解析为 UUID，格式非法时返回 null 交由上层按“不存在”处理 */
    static UUID uuidOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** 读取 jsonb 列并反序列化为 Map */
    static Map<String, Object> json(ResultSet rs, String column) throws SQLException {
        String raw = rs.getString(column);
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(raw, JSON_OBJECT);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Unable to deserialize jsonb column " + column, exception);
        }
    }

    /** 序列化为 jsonb 字面量，配合 SQL 中的 ::jsonb 转换使用 */
    static String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize value to jsonb", exception);
        }
    }
}
