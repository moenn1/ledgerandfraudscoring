package com.ledgerforge.payments.fraud;

import com.ledgerforge.payments.common.web.CorrelationIds;
import com.ledgerforge.payments.fraud.api.ReviewCaseResponse;
import com.ledgerforge.payments.fraud.api.ReviewDecisionRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/fraud")
public class FraudController {

    private final ManualReviewService manualReviewService;

    public FraudController(ManualReviewService manualReviewService) {
        this.manualReviewService = manualReviewService;
    }

    @GetMapping("/reviews")
    public List<ReviewCaseResponse> reviews() {
        return manualReviewService.listQueue().stream().map(ReviewCaseResponse::from).toList();
    }

    @PostMapping("/reviews/{id}/decision")
    public ReviewCaseResponse decide(@PathVariable UUID id,
                                     @Valid @RequestBody ReviewDecisionRequest request,
                                     HttpServletRequest httpRequest) {
        String correlationId = CorrelationIds.resolve(httpRequest);
        return ReviewCaseResponse.from(manualReviewService.decide(id, request, correlationId));
    }
}
