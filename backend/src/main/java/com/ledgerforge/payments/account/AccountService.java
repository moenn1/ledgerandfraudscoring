package com.ledgerforge.payments.account;

import com.ledgerforge.payments.account.api.AccountBalanceResponse;
import com.ledgerforge.payments.account.api.CreateAccountRequest;
import com.ledgerforge.payments.audit.AuditService;
import com.ledgerforge.payments.common.api.ApiException;
import com.ledgerforge.payments.ledger.LedgerEntryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final AuditService auditService;

    public AccountService(AccountRepository accountRepository,
                          LedgerEntryRepository ledgerEntryRepository,
                          AuditService auditService) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.auditService = auditService;
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

    @Transactional
    public AccountEntity updateStatus(UUID id, String requestedStatus, String reason, String correlationId) {
        AccountEntity account = get(id);
        AccountStatus targetStatus = normalizeMutableStatus(requestedStatus);
        if (account.getStatus() == targetStatus) {
            return account;
        }
        if (account.getStatus() == AccountStatus.CLOSED) {
            throw new ApiException(HttpStatus.CONFLICT, "Closed accounts cannot be reactivated or frozen");
        }

        AccountStatus previousStatus = account.getStatus();
        account.setStatus(targetStatus);
        AccountEntity saved = accountRepository.save(account);
        auditService.append(
                "account.status_changed",
                null,
                saved.getId(),
                null,
                correlationId,
                Map.of(
                        "previousStatus", previousStatus.name(),
                        "status", saved.getStatus().name(),
                        "reason", nullToBlank(reason)
                )
        );
        return saved;
    }

    @Transactional(readOnly = true)
    public void requirePaymentParticipationAllowed(AccountEntity account, String role) {
        if (account.getStatus() == AccountStatus.FROZEN) {
            throw new ApiException(HttpStatus.CONFLICT, capitalize(role) + " account is frozen: " + account.getId());
        }
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, capitalize(role) + " account is not active: " + account.getId());
        }
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

    private AccountStatus normalizeMutableStatus(String requestedStatus) {
        if (requestedStatus == null || requestedStatus.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Account status is required");
        }

        AccountStatus status;
        try {
            status = AccountStatus.valueOf(requestedStatus.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported account status: " + requestedStatus);
        }

        if (status != AccountStatus.ACTIVE && status != AccountStatus.FROZEN) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Only ACTIVE and FROZEN are supported by the status update workflow"
            );
        }
        return status;
    }

    private String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "Account";
        }
        String trimmed = value.trim();
        return Character.toUpperCase(trimmed.charAt(0)) + trimmed.substring(1);
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}
