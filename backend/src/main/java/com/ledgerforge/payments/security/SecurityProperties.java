package com.ledgerforge.payments.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "ledgerforge.security")
public class SecurityProperties {

    private final OAuth2 oauth2 = new OAuth2();
    private final Cors cors = new Cors();

    public OAuth2 getOauth2() {
        return oauth2;
    }

    public Cors getCors() {
        return cors;
    }

    public static class OAuth2 {

        private String issuerUri;
        private String jwkSetUri;
        private String issuer = "https://auth.ledgerforge.local";
        private String audience = "ledgerforge-operator-api";
        private String hmacSecret = "ledgerforge-dev-operator-signing-secret-change-before-shared-envs";
        private String principalClaim = "sub";

        public String getIssuerUri() {
            return issuerUri;
        }

        public void setIssuerUri(String issuerUri) {
            this.issuerUri = issuerUri;
        }

        public String getJwkSetUri() {
            return jwkSetUri;
        }

        public void setJwkSetUri(String jwkSetUri) {
            this.jwkSetUri = jwkSetUri;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = audience;
        }

        public String getHmacSecret() {
            return hmacSecret;
        }

        public void setHmacSecret(String hmacSecret) {
            this.hmacSecret = hmacSecret;
        }

        public String getPrincipalClaim() {
            return principalClaim;
        }

        public void setPrincipalClaim(String principalClaim) {
            this.principalClaim = principalClaim;
        }
    }

    public static class Cors {

        private List<String> allowedOrigins = new ArrayList<>(List.of(
                "http://127.0.0.1:5173",
                "http://localhost:5173",
                "http://127.0.0.1:3000",
                "http://localhost:3000"
        ));

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }
    }
}
