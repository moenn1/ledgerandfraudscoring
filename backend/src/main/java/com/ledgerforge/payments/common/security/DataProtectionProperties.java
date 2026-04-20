package com.ledgerforge.payments.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ledgerforge.data-protection")
public class DataProtectionProperties {

    private String encryptionKey = "ledgerforge-dev-data-protection-key-change-before-shared-envs";

    public String getEncryptionKey() {
        return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }
}
