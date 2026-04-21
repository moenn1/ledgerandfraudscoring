package com.ledgerforge.payments.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerforge.payments.account.AccountEntity;
import com.ledgerforge.payments.account.AccountRepository;
import com.ledgerforge.payments.account.AccountStatus;
import com.ledgerforge.payments.audit.AuditEventRepository;
import com.ledgerforge.payments.fraud.ReviewCaseEntity;
import com.ledgerforge.payments.fraud.ReviewCaseRepository;
import com.ledgerforge.payments.payment.api.ConfirmPaymentRequest;
import com.ledgerforge.payments.payment.api.CreatePaymentRequest;
import com.ledgerforge.payments.payment.api.RefundPaymentRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OperatorApiAuthorizationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ReviewCaseRepository reviewCaseRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Test
    void captureRequiresAuthenticatedOperator() throws Exception {
        String paymentId = createReservedPayment("capture-auth");

        mockMvc.perform(post("/api/payments/{id}/capture", paymentId)
                        .header("Idempotency-Key", "capture-auth-capture-unauth"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication is required to access protected operator APIs"));

        mockMvc.perform(post("/api/payments/{id}/capture", paymentId)
                        .header("Authorization", TestOperatorTokens.bearer("viewer.capture@ledgerforge.local", "VIEWER"))
                        .header("Idempotency-Key", "capture-auth-capture-viewer"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Authenticated operator is not permitted to perform this action"));

        mockMvc.perform(post("/api/payments/{id}/capture", paymentId)
                        .header("Authorization", TestOperatorTokens.bearer("operator.capture@ledgerforge.local", "OPERATOR"))
                        .header("Idempotency-Key", "capture-auth-capture-operator"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));
    }

    @Test
    void refundRequiresAuthenticatedOperator() throws Exception {
        String paymentId = createCapturedPayment("refund-auth");

        mockMvc.perform(post("/api/payments/{id}/refund", paymentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "refund-auth-refund-unauth")
                        .content(objectMapper.writeValueAsString(new RefundPaymentRequest(null, null, "refund"))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/payments/{id}/refund", paymentId)
                        .header("Authorization", TestOperatorTokens.bearer("viewer.refund@ledgerforge.local", "VIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "refund-auth-refund-viewer")
                        .content(objectMapper.writeValueAsString(new RefundPaymentRequest(null, null, "refund"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/payments/{id}/refund", paymentId)
                        .header("Authorization", TestOperatorTokens.bearer("operator.refund@ledgerforge.local", "OPERATOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "refund-auth-refund-operator")
                        .content(objectMapper.writeValueAsString(new RefundPaymentRequest(null, null, "refund"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));
    }

    @Test
    void cancelRequiresAuthenticatedOperator() throws Exception {
        String paymentId = createCreatedPayment("cancel-auth");

        mockMvc.perform(post("/api/payments/{id}/cancel", paymentId)
                        .header("Idempotency-Key", "cancel-auth-cancel-unauth"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/payments/{id}/cancel", paymentId)
                        .header("Authorization", TestOperatorTokens.bearer("viewer.cancel@ledgerforge.local", "VIEWER"))
                        .header("Idempotency-Key", "cancel-auth-cancel-viewer"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/payments/{id}/cancel", paymentId)
                        .header("Authorization", TestOperatorTokens.bearer("operator.cancel@ledgerforge.local", "OPERATOR"))
                        .header("Idempotency-Key", "cancel-auth-cancel-operator"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void ledgerReplayAndVerificationRequireAdminRole() throws Exception {
        UUID accountId = createAccount("ledger-admin", "USD");

        mockMvc.perform(get("/api/ledger/replay/accounts/{accountId}", accountId)
                        .header("Authorization", TestOperatorTokens.bearer("ops.viewer@ledgerforge.local", "OPERATOR")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/ledger/replay/accounts/{accountId}", accountId)
                        .header("Authorization", TestOperatorTokens.bearer("admin.replay@ledgerforge.local", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(accountId.toString()));

        mockMvc.perform(get("/api/ledger/verification")
                        .header("Authorization", TestOperatorTokens.bearer("risk.reviewer@ledgerforge.local", "REVIEWER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/ledger/verification")
                        .header("Authorization", TestOperatorTokens.bearer("admin.verify@ledgerforge.local", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allChecksPassed").value(true));
    }

    @Test
    void reviewQueueAllowsViewerButDecisionRequiresReviewer() throws Exception {
        ReviewCaseEntity reviewCase = openReviewCase("fraud-approve-auth");

        mockMvc.perform(get("/api/fraud/reviews")
                        .header("Authorization", TestOperatorTokens.bearer("viewer.queue@ledgerforge.local", "VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(reviewCase.getId().toString()));

        mockMvc.perform(post("/api/fraud/reviews/{id}/decision", reviewCase.getId())
                        .header("Authorization", TestOperatorTokens.bearer("payments.operator@ledgerforge.local", "OPERATOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "APPROVE",
                                  "note": "operator should not approve"
                                }
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/fraud/reviews/{id}/decision", reviewCase.getId())
                        .header("Authorization", TestOperatorTokens.bearer("risk.reviewer@ledgerforge.local", "REVIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "APPROVE",
                                  "note": "approved by authenticated reviewer"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        JsonNode latestDecision = latestDecisionAudit();
        assertThat(latestDecision.get("actor").asText()).isEqualTo("risk.reviewer@ledgerforge.local");
    }

    @Test
    void reviewerCanRejectWithoutCallerSuppliedActor() throws Exception {
        ReviewCaseEntity reviewCase = openReviewCase("fraud-reject-auth");

        mockMvc.perform(post("/api/fraud/reviews/{id}/decision", reviewCase.getId())
                        .header("Authorization", TestOperatorTokens.bearer("risk.reviewer.reject@ledgerforge.local", "REVIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "REJECT",
                                  "note": "rejected by authenticated reviewer"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        JsonNode latestDecision = latestDecisionAudit();
        assertThat(latestDecision.get("actor").asText()).isEqualTo("risk.reviewer.reject@ledgerforge.local");
    }

    private String createCreatedPayment(String prefix) throws Exception {
        UUID payerId = createAccount(prefix + "-payer", "USD");
        UUID payeeId = createAccount(prefix + "-payee", "USD");

        String response = mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", prefix + "-create")
                        .content(objectMapper.writeValueAsString(
                                new CreatePaymentRequest(payerId, payeeId, null, 15_000L, "USD", prefix + "-create")
                        )))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(response).get("id").asText();
    }

    private String createReservedPayment(String prefix) throws Exception {
        String paymentId = createCreatedPayment(prefix);
        mockMvc.perform(post("/api/payments/{id}/confirm", paymentId)
                        .header("Idempotency-Key", prefix + "-confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESERVED"));
        return paymentId;
    }

    private String createCapturedPayment(String prefix) throws Exception {
        String paymentId = createReservedPayment(prefix);
        mockMvc.perform(post("/api/payments/{id}/capture", paymentId)
                        .header("Authorization", TestOperatorTokens.bearer("operator.capture@ledgerforge.local", "OPERATOR"))
                        .header("Idempotency-Key", prefix + "-capture"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));
        return paymentId;
    }

    private ReviewCaseEntity openReviewCase(String prefix) throws Exception {
        UUID payerId = createAccount(prefix + "-payer", "USD");
        UUID payeeId = createAccount(prefix + "-payee", "USD");

        String createResponse = mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", prefix + "-create")
                        .content(objectMapper.writeValueAsString(
                                new CreatePaymentRequest(payerId, payeeId, null, 125_000L, "USD", prefix + "-create")
                        )))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String paymentId = objectMapper.readTree(createResponse).get("id").asText();

        mockMvc.perform(post("/api/payments/{id}/confirm", paymentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", prefix + "-confirm")
                        .content(objectMapper.writeValueAsString(
                                new ConfirmPaymentRequest(true, "MA", "US", 2, 120)
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RISK_SCORING"))
                .andExpect(jsonPath("$.riskDecision").value("REVIEW"));

        return reviewCaseRepository.findQueue().stream()
                .filter(candidate -> paymentId.equals(candidate.getPaymentId().toString()))
                .findFirst()
                .orElseThrow();
    }

    private UUID createAccount(String ownerId, String currency) {
        AccountEntity account = new AccountEntity();
        account.setOwnerId(ownerId);
        account.setCurrency(currency);
        account.setStatus(AccountStatus.ACTIVE);
        return accountRepository.save(account).getId();
    }

    private JsonNode latestDecisionAudit() throws Exception {
        String detailsJson = auditEventRepository.findAll().stream()
                .filter(event -> "fraud.review_case.decided".equals(event.getEventType()))
                .max(Comparator.comparing(event -> event.getCreatedAt()))
                .orElseThrow()
                .getDetailsJson();
        return objectMapper.readTree(detailsJson);
    }
}
