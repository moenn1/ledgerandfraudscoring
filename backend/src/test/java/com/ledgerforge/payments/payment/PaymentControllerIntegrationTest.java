package com.ledgerforge.payments.payment;

import com.ledgerforge.payments.account.AccountEntity;
import com.ledgerforge.payments.account.AccountRepository;
import com.ledgerforge.payments.account.AccountService;
import com.ledgerforge.payments.account.AccountStatus;
import com.ledgerforge.payments.account.api.CreateAccountRequest;
import com.ledgerforge.payments.fraud.ReviewCaseRepository;
import com.ledgerforge.payments.ledger.LedgerEntryRepository;
import com.ledgerforge.payments.payment.api.ConfirmPaymentRequest;
import com.ledgerforge.payments.payment.api.CreatePaymentRequest;
import com.ledgerforge.payments.payment.api.PaymentAdjustmentRequest;
import com.ledgerforge.payments.payment.api.RefundPaymentRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser(roles = "ADMIN")
class PaymentControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountService accountService;

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
                .andExpect(jsonPath("$.idempotencyKey").value(masked("key-create-1")))
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
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.idempotencyKey").value(masked("key-create-1")));

        mockMvc.perform(get("/api/payments/{id}", paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotencyKey").value(masked("key-create-1")));

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
    void reviewDecision_createsQueueEntry_andManualApproveReservesPayment() throws Exception {
        UUID payerId = createAccount("payer-review-1", "USD");
        UUID payeeId = createAccount("payee-review-1", "USD");

        String paymentId = createPayment(payerId, payeeId, 150_000L, "review-create-1");

        ConfirmPaymentRequest reviewRequest = new ConfirmPaymentRequest(true, "US", "CA", 0, 15);
        mockMvc.perform(post("/api/payments/{id}/confirm", paymentId)
                        .header("Idempotency-Key", "review-confirm-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reviewRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RISK_SCORING"))
                .andExpect(jsonPath("$.riskDecision").value("REVIEW"));

        mockMvc.perform(get("/api/payments/{id}/risk", paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(paymentId))
                .andExpect(jsonPath("$.riskDecision").value("REVIEW"))
                .andExpect(jsonPath("$.reasons.length()").value(3));

        String queueResponse = mockMvc.perform(get("/api/fraud/reviews"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode reviewCases = objectMapper.readTree(queueResponse);
        JsonNode matchingReviewCase = null;
        for (JsonNode reviewCase : reviewCases) {
            if (paymentId.equals(reviewCase.path("paymentId").asText())) {
                matchingReviewCase = reviewCase;
                break;
            }
        }

        assertThat(matchingReviewCase)
                .as("review queue entry for payment %s", paymentId)
                .isNotNull();

        String reviewCaseId = matchingReviewCase.get("id").asText();

        mockMvc.perform(post("/api/fraud/reviews/{id}/decision", reviewCaseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "APPROVE",
                                  "note": "approved after manual checks"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mockMvc.perform(post("/api/fraud/reviews/{id}/decision", reviewCaseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "APPROVE"
                                }
                                """))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/payments/{id}", paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESERVED"));
    }

    @Test
    void rejectDecision_doesNotCreateReviewQueueEntry() throws Exception {
        UUID payerId = createAccount("payer-reject-1", "USD");
        UUID payeeId = createAccount("payee-reject-1", "USD");

        String paymentId = createPayment(payerId, payeeId, 200_000L, "reject-create-1");

        ConfirmPaymentRequest rejectRequest = new ConfirmPaymentRequest(true, "US", "CA", 2, 10);
        mockMvc.perform(post("/api/payments/{id}/confirm", paymentId)
                        .header("Idempotency-Key", "reject-confirm-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rejectRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.riskDecision").value("REJECT"));

        mockMvc.perform(get("/api/payments/{id}/risk", paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskDecision").value("REJECT"))
                .andExpect(jsonPath("$.reasons.length()").value(4));

        assertThat(reviewCaseRepository.findByPaymentId(UUID.fromString(paymentId))).isEmpty();
    }

    @Test
    void frozenAccounts_blockNewMoneyMovement_butStillAllowReversal() throws Exception {
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

        String paymentId = createPayment(payerId, payeeId, 12_500L, "freeze-reversal-create");
        mockMvc.perform(post("/api/payments/{id}/confirm", paymentId)
                        .header("Idempotency-Key", "freeze-reversal-confirm"))
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
                        .header("Idempotency-Key", "freeze-reversal-capture"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Payer account is frozen: " + payerId));

        mockMvc.perform(post("/api/payments/{id}/reverse", paymentId)
                        .header("Idempotency-Key", "freeze-reversal-action")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PaymentAdjustmentRequest(null, null, "return held funds"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVERSED"));
    }

    @Test
    void multiCurrencyAccounts_canSettlePaymentInNonPrimaryCurrency() throws Exception {
        AccountEntity payer = accountService.create(new CreateAccountRequest("payer-multi-eur", "USD", List.of("EUR", "USD")));
        AccountEntity payee = accountService.create(new CreateAccountRequest("payee-multi-eur", "USD", List.of("EUR", "USD")));

        CreatePaymentRequest createRequest = new CreatePaymentRequest(
                payer.getId(),
                payee.getId(),
                null,
                10_000L,
                "EUR",
                "eur-flow-create-1"
        );
        String createResponse = mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "eur-flow-create-1")
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String paymentId = objectMapper.readTree(createResponse).get("id").asText();

        mockMvc.perform(post("/api/payments/{id}/confirm", paymentId)
                        .header("Idempotency-Key", "eur-flow-confirm-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESERVED"));

        mockMvc.perform(post("/api/payments/{id}/capture", paymentId)
                        .header("Idempotency-Key", "eur-flow-capture-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));

        String balanceResponse = mockMvc.perform(get("/api/accounts/{id}/balance", payee.getId())
                        .queryParam("currency", "EUR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(balanceResponse).get("balance").decimalValue())
                .isEqualByComparingTo(new BigDecimal("97.0000"));
        assertThat(ledgerEntryRepository.findUnbalancedJournalAggregates()).isEmpty();
    }

    @Test
    void adjustmentResponses_maskIdempotencyKeys() throws Exception {
        UUID payerId = createAccount("payer-adjustment-mask-1", "USD");
        UUID payeeId = createAccount("payee-adjustment-mask-1", "USD");

        String paymentId = createPayment(payerId, payeeId, 12_500L, "adjustment-mask-create");

        mockMvc.perform(post("/api/payments/{id}/confirm", paymentId)
                        .header("Idempotency-Key", "adjustment-mask-confirm"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/payments/{id}/capture", paymentId)
                        .header("Idempotency-Key", "adjustment-mask-capture"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/payments/{id}/refund", paymentId)
                        .header("Idempotency-Key", "adjustment-mask-refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefundPaymentRequest(null, null, "mask test"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/payments/{id}/adjustments", paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].idempotencyKey").value(masked("adjustment-mask-refund")));
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

    private String masked(String value) {
        return value.length() <= 4 ? "****" : "****" + value.substring(value.length() - 4);
    }
}
