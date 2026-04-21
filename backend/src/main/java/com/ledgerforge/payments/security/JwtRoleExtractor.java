package com.ledgerforge.payments.security;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

final class JwtRoleExtractor {

    private JwtRoleExtractor() {
    }

    static Set<String> extract(Jwt jwt) {
        LinkedHashSet<String> roles = new LinkedHashSet<>();
        addClaimRoles(jwt.getClaims().get("roles"), roles);
        addClaimRoles(jwt.getClaims().get("role"), roles);
        return roles;
    }

    private static void addClaimRoles(Object claimValue, Set<String> roles) {
        if (claimValue == null) {
            return;
        }
        if (claimValue instanceof Collection<?> collection) {
            collection.stream()
                    .map(Object::toString)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .map(JwtRoleExtractor::normalize)
                    .forEach(roles::add);
            return;
        }
        if (claimValue instanceof String value) {
            Arrays.stream(value.split("[,\\s]+"))
                    .map(String::trim)
                    .filter(token -> !token.isBlank())
                    .map(JwtRoleExtractor::normalize)
                    .forEach(roles::add);
        }
    }

    private static String normalize(String role) {
        String normalized = role.toUpperCase(Locale.ROOT);
        if (normalized.startsWith("ROLE_")) {
            normalized = normalized.substring("ROLE_".length());
        }
        return normalized;
    }
}
