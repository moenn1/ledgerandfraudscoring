package com.ledgerforge.payments.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AccountCurrencyRepository extends JpaRepository<AccountCurrencyEntity, AccountCurrencyKey> {

    List<AccountCurrencyEntity> findByIdAccountIdOrderByIdCurrencyAsc(java.util.UUID accountId);

    List<AccountCurrencyEntity> findByIdAccountIdInOrderByIdAccountIdAscIdCurrencyAsc(Collection<java.util.UUID> accountIds);

    boolean existsByIdAccountIdAndIdCurrency(java.util.UUID accountId, String currency);
}
