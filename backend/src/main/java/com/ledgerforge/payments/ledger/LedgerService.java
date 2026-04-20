package com.ledgerforge.payments.ledger;

import com.ledgerforge.payments.account.AccountEntity;
import com.ledgerforge.payments.account.AccountRepository;
import com.ledgerforge.payments.account.AccountStatus;
import com.ledgerforge.payments.common.api.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
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

    public LedgerService(
            JournalTransactionRepository journalTransactionRepository,
            LedgerEntryRepository ledgerEntryRepository,
            AccountRepository accountRepository
    ) {
        this.journalTransactionRepository = journalTransactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.accountRepository = accountRepository;
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
        validateAccounts(requestedLegs);

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

    private void validateAccounts(List<LedgerLeg> legs) {
        Set<UUID> accountIds = legs.stream().map(LedgerLeg::accountId).collect(Collectors.toSet());
        Map<UUID, AccountEntity> accountsById = accountRepository.findAllById(accountIds).stream()
                .collect(Collectors.toMap(AccountEntity::getId, Function.identity()));

        if (accountsById.size() != accountIds.size()) {
            Set<UUID> missing = accountIds.stream().filter(id -> !accountsById.containsKey(id)).collect(Collectors.toSet());
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown account(s): " + missing);
        }

        for (LedgerLeg leg : legs) {
            AccountEntity account = accountsById.get(leg.accountId());
            if (account.getStatus() != AccountStatus.ACTIVE) {
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
