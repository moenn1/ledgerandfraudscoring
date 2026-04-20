package com.ledgerforge.payments.account;

import com.ledgerforge.payments.account.api.AccountBalanceResponse;
import com.ledgerforge.payments.account.api.CreateAccountRequest;
import com.ledgerforge.payments.common.api.ApiException;
import com.ledgerforge.payments.ledger.LedgerEntryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public AccountService(AccountRepository accountRepository, LedgerEntryRepository ledgerEntryRepository) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional
    public AccountEntity create(CreateAccountRequest request) {
        AccountEntity account = new AccountEntity();
        account.setOwnerId(request.ownerId().trim());
        account.setCurrency(request.currency().trim().toUpperCase());
        account.setStatus(AccountStatus.ACTIVE);
        return accountRepository.save(account);
    }

    @Transactional(readOnly = true)
    public List<AccountEntity> list() {
        return accountRepository.findAll();
    }

    @Transactional(readOnly = true)
    public AccountEntity get(UUID id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found: " + id));
    }

    @Transactional(readOnly = true)
    public AccountBalanceResponse balance(UUID id) {
        AccountEntity account = get(id);
        BigDecimal projectedBalance = ledgerEntryRepository.projectedBalance(id, account.getCurrency());
        if (projectedBalance == null) {
            projectedBalance = BigDecimal.ZERO;
        }
        return new AccountBalanceResponse(account.getId(), account.getCurrency(), projectedBalance);
    }

    @Transactional(readOnly = true)
    public AccountEntity getOrFail(UUID id) {
        return get(id);
    }

    @Transactional(readOnly = true)
    public AccountEntity getSystemHoldingAccount(String currency) {
        return accountRepository.findFirstByOwnerIdAndCurrencyAndStatus("SYSTEM_HOLDING", currency, AccountStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Missing SYSTEM_HOLDING account for " + currency));
    }

    @Transactional(readOnly = true)
    public AccountEntity getSystemRevenueAccount(String currency) {
        return accountRepository.findFirstByOwnerIdAndCurrencyAndStatus("SYSTEM_REVENUE", currency, AccountStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Missing SYSTEM_REVENUE account for " + currency));
    }
}
