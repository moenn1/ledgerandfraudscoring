package com.ledgerforge.payments.fraud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerforge.payments.account.AccountEntity;
import com.ledgerforge.payments.account.AccountRepository;
import com.ledgerforge.payments.account.AccountStatus;
import com.ledgerforge.payments.audit.AuditEventRepository;
import com.ledgerforge.payments.outbox.OutboxEventRepository;
import com.ledgerforge.payments.payment.api.CreatePaymentRequest;
import com.ledgerforge.payments.payment.api.ConfirmPaymentRequest;
import com.ledgerforge.payments.security.TestOperatorTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FraudControllerIntegrationTest {

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

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Test
    void manualReviewApprovalPublishesReserveMutationSignals() throws Exception {
        UUID payerId = createAccount("review-payer", "USD");
        UUID payeeId = createAccount("review-payee", "USD");

        String createResponse = mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "review-create-1")
                        .content(objectMapper.writeValueAsString(
                                new CreatePaymentRequest(payerId, payeeId, null, 125_000L, "USD", "review-create-1")
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String paymentId = objectMapper.readTree(createResponse).get("id").asText();

        mockMvc.perform(post("/api/payments/{id}/confirm", paymentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "review-confirm-1")
                        .content(objectMapper.writeValueAsString(
                                new ConfirmPaymentRequest(true, "MA", "US", 2, 120)
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RISK_SCORING"))
                .andExpect(jsonPath("$.riskDecision").value("REVIEW"));

        mockMvc.perform(get("/actuator/metrics/ledgerforge.fraud.review.queue.depth"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[0].value").value(1.0));

        mockMvc.perform(get("/actuator/metrics/ledgerforge.fraud.review.case.opened"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[0].value").value(1.0));

        mockMvc.perform(get("/actuator/metrics/ledgerforge.fraud.scoring.outcome.total")
                        .param("tag", "decision:REVIEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[0].value").value(1.0));

        ReviewCaseEntity reviewCase = reviewCaseRepository.findQueue().stream()
                .filter(candidate -> paymentId.equals(candidate.getPaymentId().toString()))
                .findFirst()
                .orElseThrow();

        mockMvc.perform(post("/api/fraud/reviews/{id}/decision", reviewCase.getId())
                        .header("Authorization", TestOperatorTokens.bearer("risk.reviewer@ledgerforge.local", "REVIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "APPROVE",
                                  "note": "Approved after manual review"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mockMvc.perform(get("/actuator/metrics/ledgerforge.fraud.review.case.decided")
                        .param("tag", "decision:APPROVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[0].value").value(1.0));

        mockMvc.perform(get("/actuator/metrics/ledgerforge.fraud.review.queue.depth"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[0].value").value(0.0));

        mockMvc.perform(get("/api/payments/{id}", paymentId)
                        .header("Authorization", TestOperatorTokens.bearer("viewer.review@ledgerforge.local", "VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.riskDecision").value("APPROVE"));

        String verificationResponse = mockMvc.perform(get("/api/ledger/verification")
                        .header("Authorization", TestOperatorTokens.bearer("admin.verify@ledgerforge.local", "ADMIN")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode verification = objectMapper.readTree(verificationResponse);
        assertThat(verification.get("allChecksPassed").asBoolean()).isTrue();
        assertThat(verification.get("mutationEventReconciliationFindings")).isEmpty();
        assertThat(auditEventRepository.findAll().stream().anyMatch(event ->
                paymentId.equals(String.valueOf(event.getPaymentId()))
                        && "payment.reserved".equals(event.getEventType())
                        && event.getJournalId() != null
        )).isTrue();
        assertThat(outboxEventRepository.findAll().stream().anyMatch(event ->
                paymentId.equals(String.valueOf(event.getPaymentId()))
                        && "payment.reserved".equals(event.getEventType())
                        && event.getJournalId() != null
        )).isTrue();
    }

    private UUID createAccount(String ownerId, String currency) {
        AccountEntity account = new AccountEntity();
        account.setOwnerId(ownerId);
        account.setCurrency(currency);
        account.setStatus(AccountStatus.ACTIVE);
        return accountRepository.save(account).getId();
    }
}
