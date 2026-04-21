package com.ledgerforge.payments.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class TestOperatorTokens {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();
    private static final String SECRET = "ledgerforge-dev-operator-signing-secret-change-before-shared-envs";

    private TestOperatorTokens() {
    }

    public static String bearer(String subject, String... roles) {
        return "Bearer " + token(subject, roles);
    }

    public static String token(String subject, String... roles) {
        Instant now = Instant.now();
        Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
        Map<String, Object> payload = Map.of(
                "iss", "https://auth.ledgerforge.local",
                "sub", subject,
                "aud", List.of("ledgerforge-operator-api"),
                "iat", now.getEpochSecond(),
                "exp", now.plusSeconds(3600).getEpochSecond(),
                "preferred_username", subject,
                "roles", List.copyOf(new LinkedHashSet<>(List.of(roles)))
        );

        try {
            String encodedHeader = encodeJson(header);
            String encodedPayload = encodeJson(payload);
            String signingInput = encodedHeader + "." + encodedPayload;
            return signingInput + "." + sign(signingInput);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to generate test operator token", ex);
        }
    }

    private static String encodeJson(Object value) throws JsonProcessingException {
        return BASE64_URL.encodeToString(OBJECT_MAPPER.writeValueAsBytes(value));
    }

    private static String sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return BASE64_URL.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to sign test operator token", ex);
        }
    }
}
