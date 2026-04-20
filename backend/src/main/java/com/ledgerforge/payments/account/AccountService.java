package com.ledgerforge.payments.account;

import com.ledgerforge.payments.account.api.AccountBalanceResponse;
import com.ledgerforge.payments.account.api.AccountResponse;
import com.ledgerforge.payments.account.api.CreateAccountRequest;
import com.ledgerforge.payments.audit.AuditService;
import com.ledgerforge.payments.common.api.ApiException;
import com.ledgerforge.payments.ledger.LedgerEntryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final AccountCurrencyRepository accountCurrencyRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final AuditService auditService;

    public AccountService(AccountRepository accountRepository,
                          AccountCurrencyRepository accountCurrencyRepository,
                          LedgerEntryRepository ledgerEntryRepository,
                          AuditService auditService) {
        this.accountRepository = accountRepository;
        this.accountCurrencyRepository = accountCurrencyRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.auditService = auditService;
    }

    @Transactional
    public AccountEntity create(CreateAccountRequest request) {
        String primaryCurrency = normalizeCurrency(request.currency(), "Currency is required");
        AccountEntity account = new AccountEntity();
        account.setOwnerId(request.ownerId().trim());
        account.setCurrency(primaryCurrency);
        account.setStatus(AccountStatus.ACTIVE);
        AccountEntity saved = accountRepository.save(account);

        List<AccountCurrencyEntity> permissions = normalizeSupportedCurrencies(primaryCurrency, request.supportedCurrencies()).stream()
                .map(currency -> AccountCurrencyEntity.of(saved.getId(), currency))
                .toList();
        accountCurrencyRepository.saveAll(permissions);

        return saved;
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
    public AccountBalanceResponse balance(UUID id, String currency) {
        AccountEntity account = get(id);
        String balanceCurrency = resolveBalanceCurrency(account, currency);
        BigDecimal projectedBalance = ledgerEntryRepository.projectedBalance(id, balanceCurrency);
        if (projectedBalance == null) {
            projectedBalance = BigDecimal.ZERO;
        }
        return new AccountBalanceResponse(account.getId(), balanceCurrency, projectedBalance);
    }

    @Transactional(readOnly = true)
    public AccountBalanceResponse balance(UUID id) {
        return balance(id, null);
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
    public List<String> supportedCurrencies(UUID accountId) {
        AccountEntity account = get(accountId);
        return supportedCurrencies(account);
    }

    @Transactional(readOnly = true)
    public boolean supportsCurrency(AccountEntity account, String currency) {
        String normalizedCurrency = normalizeCurrency(currency, "Currency is required");
        List<String> supportedCurrencies = supportedCurrencies(account);
        return supportedCurrencies.contains(normalizedCurrency);
    }

    @Transactional(readOnly = true)
    public void requireCurrencySupport(AccountEntity account, String currency) {
        String normalizedCurrency = normalizeCurrency(currency, "Currency is required");
        if (!supportsCurrency(account, normalizedCurrency)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Account " + account.getId() + " does not support currency " + normalizedCurrency
            );
        }
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
    public AccountResponse toResponse(AccountEntity account) {
        return AccountResponse.from(account, supportedCurrencies(account));
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> toResponses(List<AccountEntity> accounts) {
        if (accounts.isEmpty()) {
            return List.of();
        }
        List<UUID> accountIds = accounts.stream().map(AccountEntity::getId).toList();
        Map<UUID, List<String>> currenciesByAccountId = accountCurrencyRepository
                .findByIdAccountIdInOrderByIdAccountIdAscIdCurrencyAsc(accountIds)
                .stream()
                .collect(Collectors.groupingBy(
                        AccountCurrencyEntity::getAccountId,
                        Collectors.mapping(AccountCurrencyEntity::getCurrency, Collectors.toList())
                ));

        return accounts.stream()
                .map(account -> AccountResponse.from(
                        account,
                        currenciesByAccountId.getOrDefault(account.getId(), List.of(account.getCurrency()))
                ))
                .toList();
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

    @Transactional(readOnly = true)
    public AccountEntity getSystemPayoutClearingAccount(String currency) {
        return accountRepository.findFirstByOwnerIdAndCurrencyAndStatus("SYSTEM_PAYOUT_CLEARING", currency, AccountStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Missing SYSTEM_PAYOUT_CLEARING account for " + currency));
    }

    private List<String> supportedCurrencies(AccountEntity account) {
        List<String> supportedCurrencies = accountCurrencyRepository.findByIdAccountIdOrderByIdCurrencyAsc(account.getId()).stream()
                .map(AccountCurrencyEntity::getCurrency)
                .toList();
        if (!supportedCurrencies.isEmpty()) {
            return supportedCurrencies;
        }
        return List.of(account.getCurrency());
    }

    private String resolveBalanceCurrency(AccountEntity account, String requestedCurrency) {
        List<String> supportedCurrencies = supportedCurrencies(account);
        if (requestedCurrency == null || requestedCurrency.isBlank()) {
            if (supportedCurrencies.size() == 1) {
                return supportedCurrencies.get(0);
            }
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Currency is required when querying balances for a multi-currency account"
            );
        }

        String normalizedCurrency = normalizeCurrency(requestedCurrency, "Currency is required");
        if (!supportedCurrencies.contains(normalizedCurrency)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Account " + account.getId() + " does not support currency " + normalizedCurrency
            );
        }
        return normalizedCurrency;
    }

    private List<String> normalizeSupportedCurrencies(String primaryCurrency, List<String> supportedCurrencies) {
        Set<String> normalized = new LinkedHashSet<>();
        normalized.add(primaryCurrency);
        if (supportedCurrencies != null) {
            for (String currency : supportedCurrencies) {
                normalized.add(normalizeCurrency(currency, "Supported currency is required"));
            }
        }
        return normalized.stream().sorted().toList();
    }

    private String normalizeCurrency(String currency, String missingMessage) {
        if (currency == null || currency.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, missingMessage);
        }
        return currency.trim().toUpperCase();
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
            throw new ApiException(HttpStatus.BAD_REQUEST, "Only ACTIVE and FROZEN are supported by the status update workflow");
        }
        return status;
    }

    private String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "Account";
        }
        String normalized = value.trim();
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}
