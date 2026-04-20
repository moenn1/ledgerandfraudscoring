package com.ledgerforge.payments.outbox;

import com.ledgerforge.payments.outbox.api.OutboxEventResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/events/outbox")
public class OutboxController {

    private final OutboxService outboxService;

    public OutboxController(OutboxService outboxService) {
        this.outboxService = outboxService;
    }

    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    public List<OutboxEventResponse> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", defaultValue = "50") @Min(1) @Max(200) int limit
    ) {
        return outboxService.list(status, limit).stream()
                .map(event -> OutboxEventResponse.from(event, outboxService.readJson(event.getPayloadJson())))
                .toList();
    }
}
