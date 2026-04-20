package com.ledgerforge.payments.payment;

import com.ledgerforge.payments.account.AccountEntity;
import com.ledgerforge.payments.account.AccountRepository;
import com.ledgerforge.payments.account.AccountStatus;
import com.ledgerforge.payments.fraud.ReviewCaseRepository;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
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

        assertThat(paymentRepository.findByIdempotencyKey("key-create-1"))
                .isPresent()
                .get()
                .extracting(PaymentIntentEntity::getId)
                .hasToString(paymentId);
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
    void frozenAccounts_blockCreateConfirmAndCapture_butAllowCancelRecovery() throws Exception {
        UUID payerId = createAccount("payer-frozen-1", "USD");
        UUID payeeId = createAccount("payee-frozen-1", "USD");

        mockMvc.perform(post("/api/accounts/{id}/status", payerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "FROZEN",
                                  "reason": "fraud watch"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));

        CreatePaymentRequest blockedCreate = new CreatePaymentRequest(payerId, payeeId, null, 12_500L, "USD", "frozen-create-1");
        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "frozen-create-1")
                        .content(objectMapper.writeValueAsString(blockedCreate)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Payer account is frozen: " + payerId));

        mockMvc.perform(post("/api/accounts/{id}/status", payerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "ACTIVE",
                                  "reason": "watch cleared"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        String paymentId = createPayment(payerId, payeeId, 12_500L, "freeze-recovery-create");

        mockMvc.perform(post("/api/accounts/{id}/status", payeeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "FROZEN",
                                  "reason": "beneficiary review"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));

        mockMvc.perform(post("/api/payments/{id}/confirm", paymentId)
                        .header("Idempotency-Key", "freeze-recovery-confirm-blocked"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Payee account is frozen: " + payeeId));

        mockMvc.perform(post("/api/accounts/{id}/status", payeeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "ACTIVE",
                                  "reason": "beneficiary cleared"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(post("/api/payments/{id}/confirm", paymentId)
                        .header("Idempotency-Key", "freeze-recovery-confirm-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESERVED"));

        mockMvc.perform(post("/api/accounts/{id}/status", payerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "FROZEN",
                                  "reason": "charge under investigation"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));

        mockMvc.perform(post("/api/payments/{id}/capture", paymentId)
                        .header("Idempotency-Key", "freeze-recovery-capture-blocked"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Payer account is frozen: " + payerId));

        mockMvc.perform(post("/api/payments/{id}/cancel", paymentId)
                        .header("Idempotency-Key", "freeze-recovery-cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(ledgerEntryRepository.findUnbalancedJournalAggregates()).isEmpty();
    }

    @Test
    void manualReviewApproval_rejectsFrozenParticipants() throws Exception {
        UUID payerId = createAccount("payer-review-freeze-1", "USD");
        UUID payeeId = createAccount("payee-review-freeze-1", "USD");

        String paymentId = createPayment(payerId, payeeId, 150_000L, "review-freeze-create-1");

        ConfirmPaymentRequest reviewRequest = new ConfirmPaymentRequest(true, "US", "CA", 0, 15);
        String confirmResponse = mockMvc.perform(post("/api/payments/{id}/confirm", paymentId)
                        .header("Idempotency-Key", "review-freeze-confirm-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reviewRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RISK_SCORING"))
                .andExpect(jsonPath("$.riskDecision").value("REVIEW"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(confirmResponse).get("failureReason").asText()).isEqualTo("Pending manual review");

        UUID reviewCaseId = reviewCaseRepository.findQueue().stream()
                .filter(reviewCase -> reviewCase.getPaymentId().equals(UUID.fromString(paymentId)))
                .findFirst()
                .orElseThrow()
                .getId();

        mockMvc.perform(post("/api/accounts/{id}/status", payerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "FROZEN",
                                  "reason": "escalated before approval"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));

        mockMvc.perform(post("/api/fraud/reviews/{id}/decision", reviewCaseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "APPROVE",
                                  "actor": "risk.reviewer@ledgerforge.local",
                                  "note": "escalation still open"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Payer account is frozen: " + payerId));
    }

    private String createPayment(UUID payerId, UUID payeeId, long amountCents, String idempotencyKey) throws Exception {
        CreatePaymentRequest createRequest = new CreatePaymentRequest(payerId, payeeId, null, amountCents, "USD", idempotencyKey);
        String createResponse = mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", idempotencyKey)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(createResponse).get("id").asText();
    }

    private UUID createAccount(String ownerId, String currency) {
        AccountEntity account = new AccountEntity();
        account.setOwnerId(ownerId);
        account.setCurrency(currency);
        account.setStatus(AccountStatus.ACTIVE);
        return accountRepository.save(account).getId();
    }
}
