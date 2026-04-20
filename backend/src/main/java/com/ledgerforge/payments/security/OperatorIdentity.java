package com.ledgerforge.payments.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Set;
import java.util.stream.Collectors;

public record OperatorIdentity(
        String subject,
        String actorId,
        Set<String> roles
) {

    public static OperatorIdentity from(Jwt jwt) {
        String subject = jwt.getSubject();
        String actorId = firstNonBlank(
                jwt.getClaimAsString("preferred_username"),
                jwt.getClaimAsString("email"),
                subject
        );
        return new OperatorIdentity(subject, actorId, JwtRoleExtractor.extract(jwt));
    }

    public static OperatorIdentity from(Authentication authentication) {
        if (authentication == null) {
            return new OperatorIdentity("anonymous", "anonymous", Set.of());
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof Jwt jwt) {
            return from(jwt);
        }
        String actorId = firstNonBlank(authentication.getName(), "anonymous");
        Set<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .collect(Collectors.toUnmodifiableSet());
        return new OperatorIdentity(actorId, actorId, roles);
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "anonymous";
    }
}
