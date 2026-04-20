package com.ledgerforge.payments.account;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "account_currency_permissions")
public class AccountCurrencyEntity {

    @EmbeddedId
    private AccountCurrencyKey id;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static AccountCurrencyEntity of(UUID accountId, String currency) {
        AccountCurrencyEntity entity = new AccountCurrencyEntity();
        entity.setId(new AccountCurrencyKey(accountId, currency));
        return entity;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public AccountCurrencyKey getId() {
        return id;
    }

    public void setId(AccountCurrencyKey id) {
        this.id = id;
    }

    public UUID getAccountId() {
        return id == null ? null : id.getAccountId();
    }

    public String getCurrency() {
        return id == null ? null : id.getCurrency();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
