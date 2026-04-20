package com.ledgerforge.payments.ledger;

import com.ledgerforge.payments.account.AccountEntity;
import com.ledgerforge.payments.account.AccountRepository;
import com.ledgerforge.payments.account.AccountStatus;
import com.ledgerforge.payments.common.api.ApiException;
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
    private final PaymentIntentRepository paymentIntentRepository;

    public LedgerService(
            JournalTransactionRepository journalTransactionRepository,
            LedgerEntryRepository ledgerEntryRepository,
            AccountRepository accountRepository,
            PaymentIntentRepository paymentIntentRepository
    ) {
        this.journalTransactionRepository = journalTransactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.accountRepository = accountRepository;
        this.paymentIntentRepository = paymentIntentRepository;
    }

    @Transactional(readOnly = true)
    public List<LedgerEntryEntity> listByAccount(UUID accountId) {
        if (!accountRepository.existsById(accountId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Account not found: " + accountId);
        }
        return ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(accountId);
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
    public LedgerReplayResponse replayAccount(UUID accountId) {
        AccountEntity account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Account not found: " + accountId));
        List<LedgerEntryEntity> entries = ledgerEntryRepository.findByAccountIdOrderByCreatedAtAscIdAsc(accountId);

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
                account.getCurrency(),
                replayEntries.size(),
                runningBalance,
                replayEntries
        );
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
        List<CreateLedgerLegRequest> requests = new ArrayList<>();
        for (LedgerLeg leg : legs) {
            requests.add(new CreateLedgerLegRequest(
                    leg.accountId(),
                    leg.direction(),
                    leg.amount(),
                    leg.currency()
            ));
        }
        JournalResponse posted = createJournal(new CreateJournalRequest(type, referenceId, requests));
        return getJournalOrFail(posted.id());
    }

    @Transactional
    public JournalResponse createJournal(CreateJournalRequest request) {
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
            case CANCELLED, REVERSED -> List.of(
                    Set.<JournalType>of(),
                    Set.of(JournalType.RESERVE, JournalType.REVERSAL)
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

        if (accountsById.size() != accountIds.size()) {
            Set<UUID> missing = accountIds.stream().filter(id -> !accountsById.containsKey(id)).collect(Collectors.toSet());
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown account(s): " + missing);
        }

        for (LedgerLeg leg : legs) {
            AccountEntity account = accountsById.get(leg.accountId());
            if (account.getStatus() == AccountStatus.FROZEN && !allowsFrozenAccountPosting(journalType)) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "Frozen account " + account.getId() + " cannot be posted to by " + journalType.name() + " journals"
                );
            }
            if (account.getStatus() != AccountStatus.ACTIVE && account.getStatus() != AccountStatus.FROZEN) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Inactive account cannot be posted to: " + account.getId());
            }
            if (!account.getCurrency().equals(leg.currency())) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "Currency mismatch for account " + account.getId() + ": expected " + account.getCurrency() + ", got " + leg.currency()
                );
            }
        }
    }

    private boolean allowsFrozenAccountPosting(JournalType journalType) {
        return journalType == JournalType.REFUND || journalType == JournalType.REVERSAL;
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

    private record LegKey(UUID accountId, LedgerDirection direction, BigDecimal amount, String currency) {
    }
}
