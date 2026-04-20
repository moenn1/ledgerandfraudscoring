package com.ledgerforge.payments.ledger;

import com.ledgerforge.payments.account.AccountEntity;
import com.ledgerforge.payments.account.AccountCurrencyEntity;
import com.ledgerforge.payments.account.AccountCurrencyRepository;
import com.ledgerforge.payments.account.AccountRepository;
import com.ledgerforge.payments.account.AccountStatus;
import com.ledgerforge.payments.common.api.ApiException;
import com.ledgerforge.payments.outbox.OutboxService;
import com.ledgerforge.payments.payment.PaymentIntentEntity;
import com.ledgerforge.payments.payment.PaymentIntentRepository;
import com.ledgerforge.payments.payment.PaymentStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class LedgerService {

    private final JournalTransactionRepository journalTransactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final AccountRepository accountRepository;
    private final AccountCurrencyRepository accountCurrencyRepository;
    private final PaymentIntentRepository paymentIntentRepository;
    private final OutboxService outboxService;

    public LedgerService(
            JournalTransactionRepository journalTransactionRepository,
            LedgerEntryRepository ledgerEntryRepository,
            AccountRepository accountRepository,
            AccountCurrencyRepository accountCurrencyRepository,
            PaymentIntentRepository paymentIntentRepository,
            OutboxService outboxService
    ) {
        this.journalTransactionRepository = journalTransactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.accountRepository = accountRepository;
        this.accountCurrencyRepository = accountCurrencyRepository;
        this.paymentIntentRepository = paymentIntentRepository;
        this.outboxService = outboxService;
    }

    @Transactional(readOnly = true)
    public List<LedgerEntryEntity> listByAccount(UUID accountId, String currency) {
        AccountEntity account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found: " + accountId));
        if (currency == null || currency.isBlank()) {
            return ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(accountId);
        }

        String normalizedCurrency = normalizeCurrency(currency);
        ensureAccountSupportsCurrency(account, normalizedCurrency);
        return ledgerEntryRepository.findByAccountIdAndCurrencyOrderByCreatedAtDesc(accountId, normalizedCurrency);
    }

    @Transactional(readOnly = true)
    public List<LedgerEntryEntity> listByAccount(UUID accountId) {
        return listByAccount(accountId, null);
    }

    @Transactional(readOnly = true)
    public List<LedgerEntryEntity> listByReferencePrefix(String referencePrefix) {
        return ledgerEntryRepository.findByReferencePrefixOrderByCreatedAtAsc(referencePrefix);
    }

    @Transactional(readOnly = true)
    public JournalResponse getJournal(UUID journalId) {
        JournalTransactionEntity journal = getJournalOrFail(journalId);
        List<LedgerEntryEntity> entries = ledgerEntryRepository.findByJournal_IdOrderByCreatedAtAsc(journalId);
        return JournalResponse.from(journal, entries);
    }

    @Transactional(readOnly = true)
    public LedgerReplayResponse replayAccount(UUID accountId, String currency) {
        AccountEntity account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found: " + accountId));
        String replayCurrency = resolveReplayCurrency(account, currency);
        List<LedgerEntryEntity> entries = ledgerEntryRepository.findByAccountIdAndCurrencyOrderByCreatedAtAscIdAsc(accountId, replayCurrency);

        BigDecimal runningBalance = BigDecimal.ZERO;
        List<LedgerReplayResponse.ReplayEntry> replayEntries = new ArrayList<>();
        for (LedgerEntryEntity entry : entries) {
            BigDecimal signedImpact = signedAmount(entry);
            runningBalance = runningBalance.add(signedImpact);
            replayEntries.add(new LedgerReplayResponse.ReplayEntry(
                    entry.getId(),
                    entry.getJournal().getId(),
                    entry.getJournal().getType(),
                    entry.getJournal().getReferenceId(),
                    entry.getDirection(),
                    entry.getAmount(),
                    signedImpact,
                    runningBalance,
                    entry.getCreatedAt()
            ));
        }

        return new LedgerReplayResponse(
                account.getId(),
                replayCurrency,
                replayEntries.size(),
                runningBalance,
                replayEntries
        );
    }

    @Transactional(readOnly = true)
    public LedgerReplayResponse replayAccount(UUID accountId) {
        return replayAccount(accountId, null);
    }

    @Transactional(readOnly = true)
    public LedgerVerificationResponse verifyLedger() {
        List<Object[]> unbalancedRows = ledgerEntryRepository.findUnbalancedJournalAggregates();
        List<Object[]> mixedCurrencyRows = ledgerEntryRepository.findMixedCurrencyJournalAggregates();

        Set<UUID> flaggedJournalIds = new HashSet<>();
        for (Object[] row : unbalancedRows) {
            flaggedJournalIds.add((UUID) row[0]);
        }
        for (Object[] row : mixedCurrencyRows) {
            flaggedJournalIds.add((UUID) row[0]);
        }

        Map<UUID, JournalTransactionEntity> journalsById = journalTransactionRepository.findAllById(flaggedJournalIds).stream()
                .collect(Collectors.toMap(JournalTransactionEntity::getId, Function.identity()));

        List<LedgerVerificationResponse.UnbalancedJournalFinding> unbalancedJournals = unbalancedRows.stream()
                .map(row -> {
                    UUID journalId = (UUID) row[0];
                    JournalTransactionEntity journal = journalsById.get(journalId);
                    return new LedgerVerificationResponse.UnbalancedJournalFinding(
                            journalId,
                            journal.getType(),
                            journal.getReferenceId(),
                            normalizeNumeric((BigDecimal) row[1])
                    );
                })
                .toList();

        List<LedgerVerificationResponse.MixedCurrencyJournalFinding> mixedCurrencyJournals = mixedCurrencyRows.stream()
                .map(row -> {
                    UUID journalId = (UUID) row[0];
                    JournalTransactionEntity journal = journalsById.get(journalId);
                    List<String> currencies = ledgerEntryRepository.findByJournal_IdOrderByCreatedAtAsc(journalId).stream()
                            .map(LedgerEntryEntity::getCurrency)
                            .distinct()
                            .sorted()
                            .toList();
                    return new LedgerVerificationResponse.MixedCurrencyJournalFinding(
                            journalId,
                            journal.getType(),
                            journal.getReferenceId(),
                            currencies
                    );
                })
                .toList();

        List<LedgerVerificationResponse.PaymentLifecycleMismatchFinding> paymentLifecycleMismatches = findPaymentLifecycleMismatches();
        int issueCount = unbalancedJournals.size() + mixedCurrencyJournals.size() + paymentLifecycleMismatches.size();

        return new LedgerVerificationResponse(
                java.time.Instant.now(),
                journalTransactionRepository.count(),
                ledgerEntryRepository.count(),
                issueCount == 0,
                issueCount,
                unbalancedJournals,
                mixedCurrencyJournals,
                paymentLifecycleMismatches
        );
    }

    @Transactional
    public JournalTransactionEntity postJournal(JournalType type, String referenceId, List<LedgerLeg> legs) {
        return postJournal(type, referenceId, legs, null);
    }

    @Transactional
    public JournalTransactionEntity postJournal(JournalType type,
                                                String referenceId,
                                                List<LedgerLeg> legs,
                                                String correlationId) {
        List<CreateLedgerLegRequest> requests = new ArrayList<>();
        for (LedgerLeg leg : legs) {
            requests.add(new CreateLedgerLegRequest(
                    leg.accountId(),
                    leg.direction(),
                    leg.amount(),
                    leg.currency()
            ));
        }
        JournalResponse posted = createJournal(new CreateJournalRequest(type, referenceId, requests), correlationId);
        return getJournalOrFail(posted.id());
    }

    @Transactional
    public JournalResponse createJournal(CreateJournalRequest request) {
        return createJournal(request, null);
    }

    @Transactional
    public JournalResponse createJournal(CreateJournalRequest request, String correlationId) {
        List<LedgerLeg> requestedLegs = normalizeAndValidate(request.entries());
        String referenceId = normalizeReference(request.referenceId());

        if (referenceId != null) {
            JournalTransactionEntity existing = journalTransactionRepository
                    .findFirstByTypeAndReferenceId(request.type(), referenceId)
                    .orElse(null);
            if (existing != null) {
                assertIdempotentMatch(existing, requestedLegs);
                return getJournal(existing.getId());
            }
        }

        validateLedgerInvariants(requestedLegs);
        validateAccounts(request.type(), requestedLegs);

        JournalTransactionEntity journal = new JournalTransactionEntity();
        journal.setType(request.type());
        journal.setStatus(JournalStatus.COMMITTED);
        journal.setReferenceId(referenceId);

        try {
            JournalTransactionEntity savedJournal = journalTransactionRepository.save(journal);
            List<LedgerEntryEntity> entries = requestedLegs.stream()
                    .map(leg -> toLedgerEntry(savedJournal, leg))
                    .toList();
            List<LedgerEntryEntity> savedEntries = ledgerEntryRepository.saveAll(entries);
            publishJournalCommitted(savedJournal, savedEntries, correlationId);
            return JournalResponse.from(savedJournal, savedEntries);
        } catch (DataIntegrityViolationException ex) {
            if (referenceId == null) {
                throw ex;
            }
            JournalTransactionEntity existing = journalTransactionRepository
                    .findFirstByTypeAndReferenceId(request.type(), referenceId)
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "Duplicate journal reference"));
            assertIdempotentMatch(existing, requestedLegs);
            return getJournal(existing.getId());
        }
    }

    private void publishJournalCommitted(JournalTransactionEntity journal,
                                         List<LedgerEntryEntity> entries,
                                         String correlationId) {
        BigDecimal totalDebit = entries.stream()
                .filter(entry -> entry.getDirection() == LedgerDirection.DEBIT)
                .map(LedgerEntryEntity::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = entries.stream()
                .filter(entry -> entry.getDirection() == LedgerDirection.CREDIT)
                .map(LedgerEntryEntity::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        outboxService.enqueue(
                "ledger.journal.committed",
                "journal",
                journal.getId(),
                journal.getId().toString(),
                correlationId,
                null,
                Map.of(
                        "journalId", journal.getId(),
                        "journalType", journal.getType().name(),
                        "status", journal.getStatus().name(),
                        "referenceId", journal.getReferenceId() == null ? "" : journal.getReferenceId(),
                        "entryCount", entries.size(),
                        "totalDebit", totalDebit,
                        "totalCredit", totalCredit,
                        "entries", entries.stream().map(entry -> Map.of(
                                "ledgerEntryId", entry.getId(),
                                "accountId", entry.getAccountId(),
                                "direction", entry.getDirection().name(),
                                "amount", entry.getAmount(),
                                "currency", entry.getCurrency()
                        )).toList()
                )
        );
    }

    private JournalTransactionEntity getJournalOrFail(UUID journalId) {
        return journalTransactionRepository.findById(journalId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Journal not found: " + journalId));
    }

    private List<LedgerVerificationResponse.PaymentLifecycleMismatchFinding> findPaymentLifecycleMismatches() {
        Map<UUID, Set<JournalType>> actualJournalTypesByPayment = new HashMap<>();
        for (JournalTransactionEntity journal : journalTransactionRepository.findAllByReferenceIdStartingWithOrderByCreatedAtAsc("payment:")) {
            UUID paymentId = parsePaymentId(journal.getReferenceId());
            if (paymentId == null) {
                continue;
            }
            actualJournalTypesByPayment
                    .computeIfAbsent(paymentId, ignored -> new HashSet<>())
                    .add(journal.getType());
        }

        return paymentIntentRepository.findAllByOrderByCreatedAtAsc().stream()
                .map(payment -> toLifecycleMismatch(payment, actualJournalTypesByPayment.getOrDefault(payment.getId(), Set.of())))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private LedgerVerificationResponse.PaymentLifecycleMismatchFinding toLifecycleMismatch(
            PaymentIntentEntity payment,
            Set<JournalType> actualJournalTypes
    ) {
        List<Set<JournalType>> acceptedJournalTypeSets = expectedJournalTypes(payment.getStatus());
        boolean matches = acceptedJournalTypeSets.stream().anyMatch(actualJournalTypes::equals);
        if (matches) {
            return null;
        }

        Set<JournalType> closestExpectedSet = acceptedJournalTypeSets.stream()
                .min(Comparator.<Set<JournalType>>comparingInt(expected -> symmetricDifferenceSize(expected, actualJournalTypes))
                        .thenComparingInt(Set::size))
                .orElse(Set.of());

        List<JournalType> actualTypes = sortedJournalTypes(actualJournalTypes);
        List<List<JournalType>> acceptedTypes = acceptedJournalTypeSets.stream()
                .map(this::sortedJournalTypes)
                .toList();
        List<JournalType> missingTypes = difference(closestExpectedSet, actualJournalTypes);
        List<JournalType> unexpectedTypes = difference(actualJournalTypes, closestExpectedSet);

        return new LedgerVerificationResponse.PaymentLifecycleMismatchFinding(
                payment.getId(),
                payment.getStatus(),
                actualTypes,
                acceptedTypes,
                missingTypes,
                unexpectedTypes,
                "Payment status " + payment.getStatus().name() + " has journal types " + actualTypes
                        + " but expected one of " + acceptedTypes
        );
    }

    private List<Set<JournalType>> expectedJournalTypes(PaymentStatus status) {
        return switch (status) {
            case CREATED, VALIDATED, RISK_SCORING, APPROVED, REJECTED -> List.of(Set.<JournalType>of());
            case RESERVED -> List.of(Set.of(JournalType.RESERVE));
            case CAPTURED, SETTLED -> List.of(Set.of(JournalType.RESERVE, JournalType.CAPTURE));
            case REFUNDED -> List.of(Set.of(JournalType.RESERVE, JournalType.CAPTURE, JournalType.REFUND));
            case CHARGEBACK -> List.of(Set.of(JournalType.RESERVE, JournalType.CAPTURE, JournalType.CHARGEBACK));
            case CANCELLED -> List.of(
                    Set.<JournalType>of(),
                    Set.of(JournalType.RESERVE, JournalType.REVERSAL)
            );
            case REVERSED -> List.of(
                    Set.of(JournalType.RESERVE, JournalType.REVERSAL),
                    Set.of(JournalType.RESERVE, JournalType.CAPTURE, JournalType.REVERSAL)
            );
        };
    }

    private UUID parsePaymentId(String referenceId) {
        if (referenceId == null || !referenceId.startsWith("payment:")) {
            return null;
        }
        String[] parts = referenceId.split(":");
        if (parts.length < 3) {
            return null;
        }
        try {
            return UUID.fromString(parts[1]);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private int symmetricDifferenceSize(Set<JournalType> left, Set<JournalType> right) {
        return difference(left, right).size() + difference(right, left).size();
    }

    private List<JournalType> difference(Set<JournalType> left, Set<JournalType> right) {
        return left.stream()
                .filter(type -> !right.contains(type))
                .sorted(Comparator.comparing(Enum::name))
                .toList();
    }

    private List<JournalType> sortedJournalTypes(Set<JournalType> journalTypes) {
        return journalTypes.stream()
                .sorted(Comparator.comparing(Enum::name))
                .toList();
    }

    private BigDecimal signedAmount(LedgerEntryEntity entry) {
        return entry.getDirection() == LedgerDirection.CREDIT ? entry.getAmount() : entry.getAmount().negate();
    }

    private BigDecimal normalizeNumeric(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount.stripTrailingZeros();
    }

    private List<LedgerLeg> normalizeAndValidate(List<CreateLedgerLegRequest> requestedLegs) {
        if (requestedLegs == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A journal must contain entries");
        }
        if (requestedLegs.size() < 2) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A journal must contain at least two entries");
        }
        return requestedLegs.stream()
                .map(leg -> new LedgerLeg(
                        leg.accountId(),
                        leg.direction(),
                        leg.amount(),
                        leg.currency()
                ))
                .toList();
    }

    private void validateLedgerInvariants(List<LedgerLeg> legs) {
        String journalCurrency = null;
        BigDecimal debitTotal = BigDecimal.ZERO;
        BigDecimal creditTotal = BigDecimal.ZERO;
        for (LedgerLeg leg : legs) {
            if (leg.amount() == null || leg.amount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Ledger entry amounts must be positive");
            }
            if (journalCurrency == null) {
                journalCurrency = leg.currency();
            } else if (!journalCurrency.equals(leg.currency())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "All ledger entries in a journal must share one currency");
            }
            if (leg.direction() == LedgerDirection.DEBIT) {
                debitTotal = debitTotal.add(leg.amount());
            } else if (leg.direction() == LedgerDirection.CREDIT) {
                creditTotal = creditTotal.add(leg.amount());
            } else {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown ledger direction");
            }
        }
        if (debitTotal.compareTo(creditTotal) != 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Journal is not balanced: total debits must equal total credits");
        }
    }

    private void validateAccounts(JournalType journalType, List<LedgerLeg> legs) {
        Set<UUID> accountIds = legs.stream().map(LedgerLeg::accountId).collect(Collectors.toSet());
        Map<UUID, AccountEntity> accountsById = accountRepository.findAllById(accountIds).stream()
                .collect(Collectors.toMap(AccountEntity::getId, Function.identity()));
        Map<UUID, Set<String>> supportedCurrenciesByAccountId = accountCurrencyRepository
                .findByIdAccountIdInOrderByIdAccountIdAscIdCurrencyAsc(accountIds)
                .stream()
                .collect(Collectors.groupingBy(
                        AccountCurrencyEntity::getAccountId,
                        Collectors.mapping(AccountCurrencyEntity::getCurrency, Collectors.toSet())
                ));

        if (accountsById.size() != accountIds.size()) {
            Set<UUID> missing = accountIds.stream().filter(id -> !accountsById.containsKey(id)).collect(Collectors.toSet());
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown account(s): " + missing);
        }

        for (LedgerLeg leg : legs) {
            AccountEntity account = accountsById.get(leg.accountId());
            if (!canPostToAccount(journalType, account.getStatus())) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "Account " + account.getId() + " in status " + account.getStatus().name()
                                + " cannot be posted to by " + journalType.name() + " journals"
                );
            }
            if (!accountSupportsCurrency(account, supportedCurrenciesByAccountId.get(account.getId()), leg.currency())) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "Account " + account.getId() + " does not support currency " + leg.currency()
                );
            }
        }
    }

    private LedgerEntryEntity toLedgerEntry(JournalTransactionEntity journal, LedgerLeg leg) {
        LedgerEntryEntity entry = new LedgerEntryEntity();
        entry.setJournal(journal);
        entry.setAccountId(leg.accountId());
        entry.setDirection(leg.direction());
        entry.setAmount(leg.amount());
        entry.setCurrency(leg.currency());
        return entry;
    }

    private void assertIdempotentMatch(JournalTransactionEntity existingJournal, List<LedgerLeg> requestedLegs) {
        List<LedgerEntryEntity> existingEntries = ledgerEntryRepository.findByJournal_IdOrderByCreatedAtAsc(existingJournal.getId());
        Map<LegKey, Long> existing = toLegCounts(existingEntries.stream()
                .map(entry -> new LedgerLeg(entry.getAccountId(), entry.getDirection(), entry.getAmount(), entry.getCurrency()))
                .toList());
        Map<LegKey, Long> requested = toLegCounts(requestedLegs);
        if (!existing.equals(requested)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "Reference id already exists for this journal type with a different ledger payload"
            );
        }
    }

    private Map<LegKey, Long> toLegCounts(List<LedgerLeg> legs) {
        Map<LegKey, Long> counts = new HashMap<>();
        for (LedgerLeg leg : legs) {
            LegKey key = new LegKey(
                    leg.accountId(),
                    leg.direction(),
                    leg.amount().stripTrailingZeros(),
                    leg.currency()
            );
            counts.merge(key, 1L, Long::sum);
        }
        return counts;
    }

    private String normalizeReference(String referenceId) {
        if (referenceId == null) {
            return null;
        }
        String trimmed = referenceId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String resolveReplayCurrency(AccountEntity account, String requestedCurrency) {
        Set<String> supportedCurrencies = supportedCurrencies(account);
        if (requestedCurrency == null || requestedCurrency.isBlank()) {
            if (supportedCurrencies.size() == 1) {
                return supportedCurrencies.iterator().next();
            }
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Currency is required when replaying balances for a multi-currency account"
            );
        }

        String normalizedCurrency = normalizeCurrency(requestedCurrency);
        ensureAccountSupportsCurrency(account, normalizedCurrency);
        return normalizedCurrency;
    }

    private void ensureAccountSupportsCurrency(AccountEntity account, String currency) {
        if (!accountSupportsCurrency(account, supportedCurrencies(account), currency)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Account " + account.getId() + " does not support currency " + currency
            );
        }
    }

    private Set<String> supportedCurrencies(AccountEntity account) {
        Set<String> currencies = accountCurrencyRepository.findByIdAccountIdOrderByIdCurrencyAsc(account.getId()).stream()
                .map(AccountCurrencyEntity::getCurrency)
                .collect(Collectors.toSet());
        if (!currencies.isEmpty()) {
            return currencies;
        }
        return Set.of(account.getCurrency());
    }

    private boolean accountSupportsCurrency(AccountEntity account, Set<String> supportedCurrencies, String currency) {
        if (supportedCurrencies == null || supportedCurrencies.isEmpty()) {
            return account.getCurrency().equals(currency);
        }
        return supportedCurrencies.contains(currency);
    }

    private boolean canPostToAccount(JournalType journalType, AccountStatus accountStatus) {
        if (accountStatus == AccountStatus.ACTIVE) {
            return true;
        }
        if (accountStatus == AccountStatus.FROZEN) {
            return journalType == JournalType.REVERSAL
                    || journalType == JournalType.REFUND
                    || journalType == JournalType.CHARGEBACK;
        }
        return false;
    }

    private String normalizeCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Currency is required");
        }
        return currency.trim().toUpperCase();
    }

    private record LegKey(UUID accountId, LedgerDirection direction, BigDecimal amount, String currency) {
    }
}
