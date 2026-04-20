package com.ledgerforge.payments.outbox;

import com.ledgerforge.payments.outbox.api.OutboxMessageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/outbox")
public class OutboxController {

    private final OutboxService outboxService;
    private final OutboxProperties outboxProperties;

    public OutboxController(OutboxService outboxService, OutboxProperties outboxProperties) {
        this.outboxService = outboxService;
        this.outboxProperties = outboxProperties;
    }

    @GetMapping("/messages")
    public List<OutboxMessageResponse> listMessages(@RequestParam(required = false) OutboxMessageStatus status,
                                                    @RequestParam(defaultValue = "50") int limit) {
        return outboxService.list(status, limit).stream().map(OutboxMessageResponse::from).toList();
    }

    @PostMapping("/process")
    public OutboxProcessingResponse process(@RequestParam(required = false) Integer limit) {
        int requestedLimit = limit == null ? outboxProperties.getRelayBatchSize() : limit;
        return outboxService.processReadyMessages(requestedLimit);
    }

    @PostMapping("/messages/{id}/requeue")
    public OutboxMessageResponse requeue(@PathVariable UUID id) {
        return OutboxMessageResponse.from(outboxService.requeue(id));
    }
}
