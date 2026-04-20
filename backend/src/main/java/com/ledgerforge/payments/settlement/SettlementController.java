package com.ledgerforge.payments.settlement;

import com.ledgerforge.payments.common.web.CorrelationIds;
import com.ledgerforge.payments.settlement.api.RunSettlementRequest;
import com.ledgerforge.payments.settlement.api.SettlementBatchResponse;
import com.ledgerforge.payments.settlement.api.SettlementRunResponse;
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
@RequestMapping("/api/settlements")
public class SettlementController {

    private final SettlementService settlementService;

    public SettlementController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @GetMapping("/batches")
    @PreAuthorize("hasRole('VIEWER')")
    public List<SettlementBatchResponse> listBatches() {
        return settlementService.listBatches().stream().map(SettlementBatchResponse::from).toList();
    }

    @PostMapping("/run")
    @PreAuthorize("hasRole('ADMIN')")
    public SettlementRunResponse runSettlements(@Valid @RequestBody(required = false) RunSettlementRequest request,
                                                HttpServletRequest servletRequest) {
        return settlementService.runSettlements(request, CorrelationIds.resolve(servletRequest));
    }
}
