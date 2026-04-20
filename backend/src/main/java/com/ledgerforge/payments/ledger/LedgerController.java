package com.ledgerforge.payments.ledger;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/ledger")
public class LedgerController {

    private final LedgerService ledgerService;

    public LedgerController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @PostMapping("/journals")
    @PreAuthorize("hasRole('ADMIN')")
    public JournalResponse createJournal(@Valid @RequestBody CreateJournalRequest request) {
        return ledgerService.createJournal(request);
    }

    @GetMapping("/journals/{journalId}")
    @PreAuthorize("hasRole('VIEWER')")
    public JournalResponse getJournal(@PathVariable UUID journalId) {
        return ledgerService.getJournal(journalId);
    }

    @GetMapping("/replay/accounts/{accountId}")
    @PreAuthorize("hasRole('ADMIN')")
    public LedgerReplayResponse replayAccount(@PathVariable UUID accountId) {
        return ledgerService.replayAccount(accountId);
    }

    @GetMapping("/verification")
    @PreAuthorize("hasRole('ADMIN')")
    public LedgerVerificationResponse verifyLedger() {
        return ledgerService.verifyLedger();
    }
}
