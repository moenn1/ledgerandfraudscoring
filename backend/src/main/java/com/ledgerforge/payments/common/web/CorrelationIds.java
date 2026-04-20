package com.ledgerforge.payments.common.web;

import jakarta.servlet.http.HttpServletRequest;

import java.util.UUID;

public final class CorrelationIds {

    public static final String HEADER = "X-Correlation-Id";

    private CorrelationIds() {
    }

    public static String resolve(HttpServletRequest request) {
        String value = request.getHeader(HEADER);
        if (value == null || value.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return value;
    }
}
