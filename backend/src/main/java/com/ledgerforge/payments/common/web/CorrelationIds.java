package com.ledgerforge.payments.common.web;

import jakarta.servlet.http.HttpServletRequest;

import java.util.regex.Pattern;
import java.util.UUID;

public final class CorrelationIds {

    public static final String HEADER = "X-Correlation-Id";
    private static final int MAX_LENGTH = 128;
    private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9._:/-]+");

    private CorrelationIds() {
    }

    public static String resolve(HttpServletRequest request) {
        String value = request.getHeader(HEADER);
        if (value == null || value.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_LENGTH || !SAFE_VALUE.matcher(normalized).matches()) {
            return UUID.randomUUID().toString();
        }
        return normalized;
    }
}
