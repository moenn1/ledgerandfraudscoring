package com.ledgerforge.payments.common.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SensitiveDataMasking {

    private static final String MASK = "****";

    private SensitiveDataMasking() {
    }

    public static String maskIdempotencyKey(String value) {
        return maskToken(value);
    }

    public static String maskSecret(String value) {
        return maskToken(value);
    }

    public static Map<String, Object> sanitizeMap(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> sanitized = new LinkedHashMap<>();
        input.forEach((key, value) -> sanitized.put(key, sanitizeValue(key, value)));
        return sanitized;
    }

    public static JsonNode sanitizeJson(JsonNode node) {
        return sanitizeJson(node, null);
    }

    private static JsonNode sanitizeJson(JsonNode node, String parentKey) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode sanitized = JsonNodeFactory.instance.objectNode();
            node.fields().forEachRemaining(entry -> sanitized.set(entry.getKey(), sanitizeJson(entry.getValue(), entry.getKey())));
            return sanitized;
        }
        if (node.isArray()) {
            ArrayNode sanitized = JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> sanitized.add(sanitizeJson(item, parentKey)));
            return sanitized;
        }
        if (isSensitiveKey(parentKey)) {
            return TextNode.valueOf(maskToken(node.asText()));
        }
        return node.deepCopy();
    }

    private static Object sanitizeValue(String key, Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> nested = new LinkedHashMap<>();
            mapValue.forEach((nestedKey, nestedValue) -> nested.put(String.valueOf(nestedKey), sanitizeValue(String.valueOf(nestedKey), nestedValue)));
            return nested;
        }
        if (value instanceof List<?> listValue) {
            return sanitizeList(key, listValue);
        }
        if (value instanceof Iterable<?> iterableValue) {
            List<Object> sanitized = new ArrayList<>();
            iterableValue.forEach(item -> sanitized.add(sanitizeValue(key, item)));
            return sanitized;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> sanitized = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                sanitized.add(sanitizeValue(key, Array.get(value, index)));
            }
            return sanitized;
        }
        if (value instanceof JsonNode jsonNode) {
            return sanitizeJson(jsonNode, key);
        }
        if (isSensitiveKey(key)) {
            return maskToken(String.valueOf(value));
        }
        return value;
    }

    private static List<Object> sanitizeList(String key, List<?> value) {
        List<Object> sanitized = new ArrayList<>(value.size());
        value.forEach(item -> sanitized.add(sanitizeValue(key, item)));
        return sanitized;
    }

    private static String maskToken(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 4) {
            return MASK;
        }
        return MASK + trimmed.substring(trimmed.length() - 4);
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String normalized = key.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return normalized.equals("idempotencykey")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("password")
                || normalized.contains("signature")
                || normalized.contains("credential")
                || normalized.equals("authorization");
    }
}
