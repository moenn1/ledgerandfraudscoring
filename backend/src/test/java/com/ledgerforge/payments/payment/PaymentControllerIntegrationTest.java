package com.ledgerforge.payments.payment;

import com.ledgerforge.payments.account.AccountEntity;
import com.ledgerforge.payments.account.AccountRepository;
import com.ledgerforge.payments.account.AccountStatus;
import com.ledgerforge.payments.fraud.ReviewCaseEntity;
import com.ledgerforge.payments.fraud.ReviewCaseRepository;
import com.ledgerforge.payments.fraud.ReviewCaseStatus;
import com.ledgerforge.payments.ledger.LedgerEntryRepository;
import com.ledgerforge.payments.payment.api.ConfirmPaymentRequest;
import com.ledgerforge.payments.payment.api.CreatePaymentRequest;
import com.ledgerforge.payments.payment.api.RefundPaymentRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PaymentControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PaymentIntentRepository paymentRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private ReviewCaseRepository reviewCaseRepository;

    @Test
    void createPayment_isIdempotentByKey() throws Exception {
        UUID payerId = createAccount("payer-1", "USD");
        UUID payeeId = createAccount("payee-1", "USD");

        CreatePaymentRequest request = new CreatePaymentRequest(payerId, payeeId, null, 12_500L, "USD", "key-create-1");
        String json = objectMapper.writeValueAsString(request);

        String first = mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "key-create-1")
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String paymentId = objectMapper.readTree(first).get("id").asText();

        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "key-create-1")
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(paymentId))
                .andExpect(jsonPath("$.status").value("CREATED"));

        CreatePaymentRequest conflict = new CreatePaymentRequest(payerId, payeeId, null, 13_500L, "USD", "key-create-1");
        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "key-create-1")
                        .content(objectMapper.writeValueAsString(conflict)))
                .andExpect(status().isConflict());

        assertThat(paymentRepository.count()).isEqualTo(1);
    }

    @Test
    void confirmCaptureRefund_flowProducesBalancedLedger() throws Exception {
        UUID payerId = createAccount("payer-2", "USD");
        UUID payeeId = createAccount("payee-2", "USD");

        CreatePaymentRequest createRequest = new CreatePaymentRequest(payerId, payeeId, null, 12_500L, "USD", "flow-create-1");
        String createResponse = mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "flow-create-1")
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String paymentId = objectMapper.readTree(createResponse).get("id").asText();

        mockMvc.perform(post("/api/payments/{id}/confirm", paymentId)
                        .header("Idempotency-Key", "flow-confirm-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESERVED"));

        mockMvc.perform(post("/api/payments/{id}/capture", paymentId)
                        .header("Idempotency-Key", "flow-capture-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));

        mockMvc.perform(post("/api/payments/{id}/refund", paymentId)
                        .header("Idempotency-Key", "flow-refund-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefundPaymentRequest(null, null, "test"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));

        mockMvc.perform(post("/api/payments/{id}/capture", paymentId)
                        .header("Idempotency-Key", "flow-capture-2"))
                .andExpect(status().isConflict());

        assertThat(ledgerEntryRepository.findUnbalancedJournalAggregates()).isEmpty();
    }

    @Test
    void fraudReviewDecisionRejectsUnsafeActorValues() throws Exception {
        UUID payerId = createAccount("payer-3", "USD");
        UUID payeeId = createAccount("payee-3", "USD");

        CreatePaymentRequest createRequest = new CreatePaymentRequest(payerId, payeeId, null, 60_000L, "USD", "review-create-unsafe-actor");
        String createResponse = mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "review-create-unsafe-actor")
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String paymentId = objectMapper.readTree(createResponse).get("id").asText();

        mockMvc.perform(post("/api/payments/{id}/confirm", paymentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "review-confirm-unsafe-actor")
                        .content(objectMapper.writeValueAsString(new ConfirmPaymentRequest(true, "US", "MA", 0, 120))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RISK_SCORING"))
                .andExpect(jsonPath("$.riskDecision").value("REVIEW"));

        ReviewCaseEntity reviewCase = reviewCaseRepository.findByPaymentId(UUID.fromString(paymentId)).orElseThrow();

        mockMvc.perform(post("/api/fraud/reviews/{id}/decision", reviewCase.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", "corr-review-unsafe-actor")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "decision", "APPROVE",
                                "actor", "reviewer.one@ledgerforge.local\nshadow",
                                "note", "approve"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed for fields: actor"));

        assertThat(reviewCaseRepository.findById(reviewCase.getId()).orElseThrow().getStatus()).isEqualTo(ReviewCaseStatus.OPEN);
        assertThat(paymentRepository.findById(UUID.fromString(paymentId)).orElseThrow().getStatus()).isEqualTo(PaymentStatus.RISK_SCORING);
    }

    private UUID createAccount(String ownerId, String currency) {
        AccountEntity account = new AccountEntity();
        account.setOwnerId(ownerId);
        account.setCurrency(currency);
        account.setStatus(AccountStatus.ACTIVE);
        return accountRepository.save(account).getId();
    }
}
