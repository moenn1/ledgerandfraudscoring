package com.ledgerforge.payments.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataMaskingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sanitizeMap_masksSensitiveKeysRecursively() {
        Map<String, Object> sanitized = SensitiveDataMasking.sanitizeMap(Map.of(
                "idempotencyKey", "pay-create-1234",
                "details", Map.of(
                        "signingSecret", "notif-secret-1234",
                        "attempts", List.of(
                                Map.of("accessToken", "token-5678"),
                                Map.of("status", "ok")
                        )
                )
        ));

        assertThat(sanitized.get("idempotencyKey")).isEqualTo("****1234");
        assertThat(((Map<?, ?>) sanitized.get("details")).get("signingSecret")).isEqualTo("****1234");
        List<?> attempts = (List<?>) ((Map<?, ?>) sanitized.get("details")).get("attempts");
        assertThat(((Map<?, ?>) attempts.get(0)).get("accessToken")).isEqualTo("****5678");
        assertThat(((Map<?, ?>) attempts.get(1)).get("status")).isEqualTo("ok");
    }

    @Test
    void sanitizeJson_masksSensitiveFieldsInLegacyPayloads() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("idempotencyKey", "evt-9876");
        payload.put("status", "CREATED");
        ObjectNode nested = payload.putObject("delivery");
        nested.put("signingSecret", "hook-secret-4321");

        assertThat(SensitiveDataMasking.sanitizeJson(payload).toPrettyString())
                .contains("****9876")
                .contains("****4321")
                .contains("CREATED")
                .doesNotContain("evt-9876")
                .doesNotContain("hook-secret-4321");
    }
}
