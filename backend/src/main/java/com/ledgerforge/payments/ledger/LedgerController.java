package com.ledgerforge.payments.ledger;

import jakarta.validation.Valid;
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
    public JournalResponse createJournal(@Valid @RequestBody CreateJournalRequest request) {
        return ledgerService.createJournal(request);
    }

    @GetMapping("/journals/{journalId}")
    public JournalResponse getJournal(@PathVariable UUID journalId) {
        return ledgerService.getJournal(journalId);
    }
}
