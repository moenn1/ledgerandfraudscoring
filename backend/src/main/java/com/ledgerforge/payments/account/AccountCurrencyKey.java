package com.ledgerforge.payments.account;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class AccountCurrencyKey implements Serializable {

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(nullable = false, length = 3)
    private String currency;

    public AccountCurrencyKey() {
    }

    public AccountCurrencyKey(UUID accountId, String currency) {
        this.accountId = accountId;
        this.currency = currency;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public void setAccountId(UUID accountId) {
        this.accountId = accountId;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AccountCurrencyKey that)) {
            return false;
        }
        return Objects.equals(accountId, that.accountId) && Objects.equals(currency, that.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accountId, currency);
    }
}
