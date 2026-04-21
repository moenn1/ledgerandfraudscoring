package com.ledgerforge.payments.outbox;

import com.ledgerforge.payments.outbox.api.OutboxEventResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/outbox")
public class OutboxController {

    private final OutboxService outboxService;

    public OutboxController(OutboxService outboxService) {
        this.outboxService = outboxService;
    }

    @GetMapping("/events")
    @PreAuthorize("hasRole('ADMIN')")
    public List<OutboxEventResponse> listEvents(@RequestParam(required = false) OutboxEventState state,
                                                @RequestParam(defaultValue = "50") int limit) {
        return outboxService.list(state, limit).stream()
                .map(OutboxEventResponse::from)
                .toList();
    }

    @PostMapping("/relay/run")
    @PreAuthorize("hasRole('ADMIN')")
    public OutboxProcessResponse runRelay(@RequestParam(defaultValue = "25") int limit) {
        return outboxService.processReadyEvents(limit);
    }

    @PostMapping("/events/{eventId}/requeue")
    @PreAuthorize("hasRole('ADMIN')")
    public OutboxEventResponse requeue(@PathVariable UUID eventId) {
        return OutboxEventResponse.from(outboxService.requeue(eventId));
    }
}
