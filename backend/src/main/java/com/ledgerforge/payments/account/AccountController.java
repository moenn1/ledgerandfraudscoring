package com.ledgerforge.payments.account;

import com.ledgerforge.payments.account.api.AccountBalanceResponse;
import com.ledgerforge.payments.account.api.AccountResponse;
import com.ledgerforge.payments.account.api.CreateAccountRequest;
import com.ledgerforge.payments.account.api.UpdateAccountStatusRequest;
import com.ledgerforge.payments.common.web.CorrelationIds;
import com.ledgerforge.payments.ledger.LedgerEntryResponse;
import com.ledgerforge.payments.ledger.LedgerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    @PreAuthorize("hasRole('ADMIN')")
    public AccountResponse create(@Valid @RequestBody CreateAccountRequest request) {
        return accountService.toResponse(accountService.create(request));
    }

    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    public List<AccountResponse> list() {
        return accountService.toResponses(accountService.list());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('VIEWER')")
    public AccountResponse get(@PathVariable UUID id) {
        return accountService.toResponse(accountService.get(id));
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public AccountResponse updateStatus(@PathVariable UUID id,
                                        @Valid @RequestBody UpdateAccountStatusRequest request,
                                        HttpServletRequest httpRequest) {
        return accountService.toResponse(
                accountService.updateStatus(id, request.status(), request.reason(), CorrelationIds.resolve(httpRequest))
        );
    }

    @GetMapping("/{id}/balance")
    @PreAuthorize("hasRole('VIEWER')")
    public AccountBalanceResponse balance(@PathVariable UUID id, @RequestParam(required = false) String currency) {
        return accountService.balance(id, currency);
    }

    @GetMapping("/{id}/ledger")
    @PreAuthorize("hasRole('VIEWER')")
    public List<LedgerEntryResponse> ledger(@PathVariable UUID id, @RequestParam(required = false) String currency) {
        return ledgerService.listByAccount(id, currency).stream().map(LedgerEntryResponse::from).toList();
    }
}
