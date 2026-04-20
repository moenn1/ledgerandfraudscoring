package com.ledgerforge.payments.settlement;

import com.ledgerforge.payments.common.web.CorrelationIds;
import com.ledgerforge.payments.settlement.api.PayoutResponse;
import com.ledgerforge.payments.settlement.api.PayoutRunResponse;
import com.ledgerforge.payments.settlement.api.RunPayoutRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/payouts")
public class PayoutController {

    private final SettlementService settlementService;

    public PayoutController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    public List<PayoutResponse> listPayouts() {
        return settlementService.listPayouts().stream().map(PayoutResponse::from).toList();
    }

    @PostMapping("/run")
    @PreAuthorize("hasRole('ADMIN')")
    public PayoutRunResponse runPayouts(@Valid @RequestBody(required = false) RunPayoutRequest request,
                                        HttpServletRequest servletRequest) {
        return settlementService.runPayouts(request, CorrelationIds.resolve(servletRequest));
    }
}
