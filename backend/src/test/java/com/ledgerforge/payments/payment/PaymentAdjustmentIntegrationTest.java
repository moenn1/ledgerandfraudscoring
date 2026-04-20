package com.ledgerforge.payments.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerforge.payments.account.AccountEntity;
import com.ledgerforge.payments.account.AccountRepository;
import com.ledgerforge.payments.account.AccountService;
import com.ledgerforge.payments.account.AccountStatus;
import com.ledgerforge.payments.ledger.LedgerEntryRepository;
import com.ledgerforge.payments.payment.api.ConfirmPaymentRequest;
import com.ledgerforge.payments.payment.api.CreatePaymentRequest;
import com.ledgerforge.payments.payment.api.PaymentAdjustmentRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
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
@WithMockUser(roles = "ADMIN")
class PaymentAdjustmentIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountService accountService;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Test
    void reverseReservedPayment_releasesHeldFunds_andAddsImmutableAdjustment() throws Exception {
        UUID payerId = createAccount("payer-reverse-reserved", "USD");
        UUID payeeId = createAccount("payee-reverse-reserved", "USD");

        String paymentId = createPayment(payerId, payeeId, 10_000L, "reverse-reserved-create");
        confirmPayment(paymentId, "reverse-reserved-confirm");

        mockMvc.perform(post("/api/payments/{id}/reverse", paymentId)
                        .header("Idempotency-Key", "reverse-reserved-action")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PaymentAdjustmentRequest(null, null, "reserve expired"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVERSED"));

        mockMvc.perform(get("/api/payments/{id}/adjustments", paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("REVERSAL"))
                .andExpect(jsonPath("$[0].feeAmount").value(0))
                .andExpect(jsonPath("$[0].reason").value("reserve expired"));

        assertThat(accountService.balance(payerId).balance()).isEqualByComparingTo("0.0000");
        assertThat(accountService.balance(accountService.getSystemHoldingAccount("USD").getId()).balance()).isEqualByComparingTo("0.0000");
        assertThat(ledgerEntryRepository.findUnbalancedJournalAggregates()).isEmpty();
    }

    @Test
    void reverseCapturedPayment_unwindsCapture_andBlocksRefunds() throws Exception {
        UUID payerId = createAccount("payer-reverse-captured", "USD");
        UUID payeeId = createAccount("payee-reverse-captured", "USD");

        String paymentId = createCapturedPayment(payerId, payeeId, 10_000L, "reverse-captured");

        mockMvc.perform(post("/api/payments/{id}/reverse", paymentId)
                        .header("Idempotency-Key", "reverse-captured-action")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PaymentAdjustmentRequest(null, null, "capture compensation"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVERSED"));

        mockMvc.perform(post("/api/payments/{id}/refund", paymentId)
                        .header("Idempotency-Key", "reverse-captured-refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PaymentAdjustmentRequest(null, null, "should fail"))))
                .andExpect(status().isConflict());

        assertThat(accountService.balance(payerId).balance()).isEqualByComparingTo("0.0000");
        assertThat(accountService.balance(payeeId).balance()).isEqualByComparingTo("0.0000");
        assertThat(accountService.balance(accountService.getSystemRevenueAccount("USD").getId()).balance()).isEqualByComparingTo("0.0000");
        assertThat(ledgerEntryRepository.findUnbalancedJournalAggregates()).isEmpty();
    }

    @Test
    void chargebackIsIdempotent_andVisibleInAdjustmentHistory() throws Exception {
        UUID payerId = createAccount("payer-chargeback", "USD");
        UUID payeeId = createAccount("payee-chargeback", "USD");

        String paymentId = createCapturedPayment(payerId, payeeId, 15_000L, "chargeback");
        PaymentAdjustmentRequest request = new PaymentAdjustmentRequest(null, null, "issuer dispute");

        mockMvc.perform(post("/api/payments/{id}/chargeback", paymentId)
                        .header("Idempotency-Key", "chargeback-action")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHARGEBACK"));

        mockMvc.perform(post("/api/payments/{id}/chargeback", paymentId)
                        .header("Idempotency-Key", "chargeback-action")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHARGEBACK"));

        mockMvc.perform(get("/api/payments/{id}/adjustments", paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("CHARGEBACK"))
                .andExpect(jsonPath("$[0].reason").value("issuer dispute"));

        mockMvc.perform(post("/api/payments/{id}/refund", paymentId)
                        .header("Idempotency-Key", "chargeback-refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PaymentAdjustmentRequest(null, null, "post-dispute refund"))))
                .andExpect(status().isConflict());
    }

    @Test
    void refundAddsImmutableHistory_andBlocksChargebacks() throws Exception {
        UUID payerId = createAccount("payer-refund-history", "USD");
        UUID payeeId = createAccount("payee-refund-history", "USD");

        String paymentId = createCapturedPayment(payerId, payeeId, 20_000L, "refund-history");

        mockMvc.perform(post("/api/payments/{id}/refund", paymentId)
                        .header("Idempotency-Key", "refund-history-action")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PaymentAdjustmentRequest(null, null, "customer return"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));

        mockMvc.perform(get("/api/payments/{id}/adjustments", paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("REFUND"))
                .andExpect(jsonPath("$[0].reason").value("customer return"));

        mockMvc.perform(post("/api/payments/{id}/chargeback", paymentId)
                        .header("Idempotency-Key", "refund-history-chargeback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PaymentAdjustmentRequest(null, null, "late dispute"))))
                .andExpect(status().isConflict());
    }

    private String createCapturedPayment(UUID payerId, UUID payeeId, long amountCents, String prefix) throws Exception {
        String paymentId = createPayment(payerId, payeeId, amountCents, prefix + "-create");
        confirmPayment(paymentId, prefix + "-confirm");
        mockMvc.perform(post("/api/payments/{id}/capture", paymentId)
                        .header("Idempotency-Key", prefix + "-capture"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));
        return paymentId;
    }

    private void confirmPayment(String paymentId, String idempotencyKey) throws Exception {
        ConfirmPaymentRequest request = new ConfirmPaymentRequest(false, "US", "US", 0, 1_440);
        mockMvc.perform(post("/api/payments/{id}/confirm", paymentId)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESERVED"));
    }

    private String createPayment(UUID payerId, UUID payeeId, long amountCents, String idempotencyKey) throws Exception {
        CreatePaymentRequest request = new CreatePaymentRequest(payerId, payeeId, null, amountCents, "USD", idempotencyKey);
        String response = mockMvc.perform(post("/api/payments")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private UUID createAccount(String ownerId, String currency) {
        AccountEntity account = new AccountEntity();
        account.setOwnerId(ownerId);
        account.setCurrency(currency);
        account.setStatus(AccountStatus.ACTIVE);
        return accountRepository.save(account).getId();
    }
}
