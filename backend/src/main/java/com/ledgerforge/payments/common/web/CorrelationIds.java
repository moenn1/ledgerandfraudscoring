package com.ledgerforge.payments.common.web;

import jakarta.servlet.http.HttpServletRequest;

import java.util.UUID;

public final class CorrelationIds {

    public static final String HEADER = "X-Correlation-Id";
    public static final String ATTRIBUTE = CorrelationIds.class.getName() + ".value";

    private CorrelationIds() {
    }

    public static String resolve(HttpServletRequest request) {
        Object current = request.getAttribute(ATTRIBUTE);
        if (current instanceof String value && !value.isBlank()) {
            return value;
        }

        String value = request.getHeader(HEADER);
        if (value == null || value.isBlank()) {
            value = UUID.randomUUID().toString();
        }
        request.setAttribute(ATTRIBUTE, value);
        return value;
    }
}
