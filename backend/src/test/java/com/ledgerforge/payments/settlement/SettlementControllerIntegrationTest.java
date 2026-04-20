package com.ledgerforge.payments.settlement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerforge.payments.account.AccountEntity;
import com.ledgerforge.payments.account.AccountRepository;
import com.ledgerforge.payments.account.AccountService;
import com.ledgerforge.payments.account.AccountStatus;
import com.ledgerforge.payments.ledger.LedgerEntryRepository;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
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
class SettlementControllerIntegrationTest {

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
    void runSettlements_groupsCapturedPaymentsIntoSingleBatchAndSchedulesOnePayout() throws Exception {
        UUID payerId = createAccount("payer-settlement-batch", "USD");
        UUID payeeId = createAccount("payee-settlement-batch", "USD");

        String firstPaymentId = createCapturedPayment(payerId, payeeId, 10_000L, "settlement-batch-1");
        String secondPaymentId = createCapturedPayment(payerId, payeeId, 20_000L, "settlement-batch-2");

        String firstPaymentResponse = mockMvc.perform(get("/api/payments/{id}", firstPaymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode firstPayment = objectMapper.readTree(firstPaymentResponse);
        Instant settlementScheduledFor = Instant.parse(firstPayment.get("settlementScheduledFor").asText());
        assertThat(settlementScheduledFor.atOffset(ZoneOffset.UTC).getHour()).isEqualTo(17);

        String settlementRunResponse = mockMvc.perform(post("/api/settlements/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "asOf": "%s",
                                  "payoutDelayMinutes": 0
                                }
                                """.formatted(settlementScheduledFor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settledPaymentCount").value(2))
                .andExpect(jsonPath("$.batchCount").value(1))
                .andExpect(jsonPath("$.payoutCount").value(1))
                .andExpect(jsonPath("$.batches[0].paymentCount").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String batchId = objectMapper.readTree(settlementRunResponse).get("batches").get(0).get("id").asText();

        mockMvc.perform(get("/api/payments/{id}", firstPaymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.settlementBatchId").value(batchId));

        mockMvc.perform(get("/api/payments/{id}", secondPaymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.settlementBatchId").value(batchId));

        mockMvc.perform(get("/api/payouts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("SCHEDULED"))
                .andExpect(jsonPath("$[0].grossAmount").value(300))
                .andExpect(jsonPath("$[0].feeAmount").value(9))
                .andExpect(jsonPath("$[0].netAmount").value(291));

        assertThat(ledgerEntryRepository.findUnbalancedJournalAggregates()).isEmpty();
    }

    @Test
    void runPayouts_movesSettledFundsIntoClearingAndSkipsAlreadyPaidPayouts() throws Exception {
        UUID payerId = createAccount("payer-payout-run", "USD");
        UUID payeeId = createAccount("payee-payout-run", "USD");

        String paymentId = createCapturedPayment(payerId, payeeId, 10_000L, "payout-run");
        Instant settlementScheduledFor = settlementScheduledFor(paymentId);

        mockMvc.perform(post("/api/settlements/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "asOf": "%s",
                                  "payoutDelayMinutes": 0
                                }
                                """.formatted(settlementScheduledFor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settledPaymentCount").value(1));

        mockMvc.perform(post("/api/payouts/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "asOf": "%s"
                                }
                                """.formatted(settlementScheduledFor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paidCount").value(1))
                .andExpect(jsonPath("$.delayedCount").value(0))
                .andExpect(jsonPath("$.payouts[0].status").value("PAID"));

        mockMvc.perform(post("/api/payouts/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "asOf": "%s"
                                }
                                """.formatted(settlementScheduledFor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paidCount").value(0))
                .andExpect(jsonPath("$.delayedCount").value(0));

        assertThat(accountService.balance(payeeId, "USD").balance()).isEqualByComparingTo("0.0000");
        assertThat(accountService.balance(accountService.getSystemPayoutClearingAccount("USD").getId(), "USD").balance())
                .isEqualByComparingTo("97.0000");
        assertThat(ledgerEntryRepository.findUnbalancedJournalAggregates()).isEmpty();
    }

    @Test
    void runPayouts_marksPayoutDelayedWhenRefundConsumesSettledBalance() throws Exception {
        UUID payerId = createAccount("payer-payout-delay", "USD");
        UUID payeeId = createAccount("payee-payout-delay", "USD");

        String paymentId = createCapturedPayment(payerId, payeeId, 10_000L, "payout-delay");
        Instant settlementScheduledFor = settlementScheduledFor(paymentId);

        mockMvc.perform(post("/api/settlements/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "asOf": "%s",
                                  "payoutDelayMinutes": 0
                                }
                                """.formatted(settlementScheduledFor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settledPaymentCount").value(1));

        mockMvc.perform(post("/api/payments/{id}/refund", paymentId)
                        .header("Idempotency-Key", "payout-delay-refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PaymentAdjustmentRequest(null, null, "customer reversal"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));

        mockMvc.perform(post("/api/payouts/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "asOf": "%s"
                                }
                                """.formatted(settlementScheduledFor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paidCount").value(0))
                .andExpect(jsonPath("$.delayedCount").value(1))
                .andExpect(jsonPath("$.payouts[0].status").value("DELAYED"))
                .andExpect(jsonPath("$.payouts[0].delayReason").value("Insufficient available balance for payout execution"));

        assertThat(accountService.balance(payeeId, "USD").balance()).isEqualByComparingTo("0.0000");
        assertThat(accountService.balance(accountService.getSystemPayoutClearingAccount("USD").getId(), "USD").balance())
                .isEqualByComparingTo("0.0000");
        assertThat(ledgerEntryRepository.findUnbalancedJournalAggregates()).isEmpty();
    }

    private Instant settlementScheduledFor(String paymentId) throws Exception {
        String paymentResponse = mockMvc.perform(get("/api/payments/{id}", paymentId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return Instant.parse(objectMapper.readTree(paymentResponse).get("settlementScheduledFor").asText());
    }

    private String createCapturedPayment(UUID payerId, UUID payeeId, long amountCents, String prefix) throws Exception {
        String paymentId = createPayment(payerId, payeeId, amountCents, prefix + "-create");
        mockMvc.perform(post("/api/payments/{id}/confirm", paymentId)
                        .header("Idempotency-Key", prefix + "-confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESERVED"));

        mockMvc.perform(post("/api/payments/{id}/capture", paymentId)
                        .header("Idempotency-Key", prefix + "-capture"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.settlementScheduledFor").exists());
        return paymentId;
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
