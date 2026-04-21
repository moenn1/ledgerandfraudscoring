package com.ledgerforge.payments.payment;

import com.ledgerforge.payments.account.AccountEntity;
import com.ledgerforge.payments.account.AccountRepository;
import com.ledgerforge.payments.account.AccountStatus;
import com.ledgerforge.payments.ledger.LedgerEntryRepository;
import com.ledgerforge.payments.payment.api.CreatePaymentRequest;
import com.ledgerforge.payments.payment.api.RefundPaymentRequest;
import com.ledgerforge.payments.security.TestOperatorTokens;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ExtendWith(OutputCaptureExtension.class)
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
                        .header("X-Correlation-Id", "corr-payment-create")
                        .header("Idempotency-Key", "flow-create-1")
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", "corr-payment-create"))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String paymentId = objectMapper.readTree(createResponse).get("id").asText();

        mockMvc.perform(post("/api/payments/{id}/confirm", paymentId)
                        .header("X-Correlation-Id", "corr-payment-confirm")
                        .header("Idempotency-Key", "flow-confirm-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", "corr-payment-confirm"))
                .andExpect(jsonPath("$.status").value("RESERVED"));

        mockMvc.perform(post("/api/payments/{id}/capture", paymentId)
                        .header("Authorization", TestOperatorTokens.bearer("operator.capture@ledgerforge.local", "OPERATOR"))
                        .header("X-Correlation-Id", "corr-payment-capture")
                        .header("Idempotency-Key", "flow-capture-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", "corr-payment-capture"))
                .andExpect(jsonPath("$.status").value("CAPTURED"));

        mockMvc.perform(post("/api/payments/{id}/refund", paymentId)
                        .header("Authorization", TestOperatorTokens.bearer("operator.refund@ledgerforge.local", "OPERATOR"))
                        .header("X-Correlation-Id", "corr-payment-refund")
                        .header("Idempotency-Key", "flow-refund-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefundPaymentRequest(null, null, "test"))))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", "corr-payment-refund"))
                .andExpect(jsonPath("$.status").value("REFUNDED"));

        mockMvc.perform(post("/api/payments/{id}/capture", paymentId)
                        .header("Authorization", TestOperatorTokens.bearer("operator.capture@ledgerforge.local", "OPERATOR"))
                        .header("Idempotency-Key", "flow-capture-2"))
                .andExpect(status().isConflict());

        assertThat(ledgerEntryRepository.findUnbalancedJournalAggregates()).isEmpty();
    }

    @Test
    void paymentTelemetry_isExposedThroughActuatorAndCorrelationLogs(CapturedOutput output) throws Exception {
        UUID payerId = createAccount("payer-telemetry", "USD");
        UUID payeeId = createAccount("payee-telemetry", "USD");

        CreatePaymentRequest createRequest = new CreatePaymentRequest(payerId, payeeId, null, 12_500L, "USD", "telemetry-create-1");
        String createResponse = mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", "corr-telemetry-create")
                        .header("Idempotency-Key", "telemetry-create-1")
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String paymentId = objectMapper.readTree(createResponse).get("id").asText();

        mockMvc.perform(post("/api/payments/{id}/confirm", paymentId)
                        .header("X-Correlation-Id", "corr-telemetry-confirm")
                        .header("Idempotency-Key", "telemetry-confirm-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESERVED"));

        mockMvc.perform(post("/api/payments/{id}/capture", paymentId)
                        .header("Authorization", TestOperatorTokens.bearer("operator.metrics@ledgerforge.local", "OPERATOR"))
                        .header("X-Correlation-Id", "corr-telemetry-capture")
                        .header("Idempotency-Key", "telemetry-capture-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));

        String paymentMetricsResponse = mockMvc.perform(get("/actuator/metrics/ledgerforge.payment.outcome.total"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(paymentMetricsResponse).path("measurements").get(0).path("value").asDouble()).isGreaterThanOrEqualTo(3.0);
        assertThat(paymentMetricsResponse).contains("risk_decision");
        assertThat(paymentMetricsResponse).contains("status");
        assertThat(paymentMetricsResponse).contains("CREATED");
        assertThat(paymentMetricsResponse).contains("RESERVED");
        assertThat(paymentMetricsResponse).contains("CAPTURED");

        assertThat(output).contains("correlation_id=corr-telemetry-confirm");
        assertThat(output).contains("path=/api/payments/" + paymentId + "/confirm");
    }

    private UUID createAccount(String ownerId, String currency) {
        AccountEntity account = new AccountEntity();
        account.setOwnerId(ownerId);
        account.setCurrency(currency);
        account.setStatus(AccountStatus.ACTIVE);
        return accountRepository.save(account).getId();
    }
}
