package com.ledgerforge.payments.account;

import com.ledgerforge.payments.account.api.AccountBalanceResponse;
import com.ledgerforge.payments.account.api.AccountResponse;
import com.ledgerforge.payments.account.api.CreateAccountRequest;
import com.ledgerforge.payments.ledger.LedgerEntryResponse;
import com.ledgerforge.payments.ledger.LedgerService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;
    private final LedgerService ledgerService;

    public AccountController(AccountService accountService, LedgerService ledgerService) {
        this.accountService = accountService;
        this.ledgerService = ledgerService;
    }

    @PostMapping
    public AccountResponse create(@Valid @RequestBody CreateAccountRequest request) {
        return AccountResponse.from(accountService.create(request));
    }

    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    public List<AccountResponse> list() {
        return accountService.list().stream().map(AccountResponse::from).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('VIEWER')")
    public AccountResponse get(@PathVariable UUID id) {
        return AccountResponse.from(accountService.get(id));
    }

    @GetMapping("/{id}/balance")
    @PreAuthorize("hasRole('VIEWER')")
    public AccountBalanceResponse balance(@PathVariable UUID id) {
        return accountService.balance(id);
    }

    @GetMapping("/{id}/ledger")
    @PreAuthorize("hasRole('VIEWER')")
    public List<LedgerEntryResponse> ledger(@PathVariable UUID id) {
        return ledgerService.listByAccount(id).stream().map(LedgerEntryResponse::from).toList();
    }
}
