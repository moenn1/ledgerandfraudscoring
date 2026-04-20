package com.ledgerforge.payments.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerforge.payments.account.AccountEntity;
import com.ledgerforge.payments.account.AccountRepository;
import com.ledgerforge.payments.account.AccountStatus;
import com.ledgerforge.payments.payment.api.CreatePaymentRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NotificationControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private NotificationEndpointRepository endpointRepository;

    @Autowired
    private NotificationDeliveryRepository deliveryRepository;

    @Autowired
    private NotificationCallbackRepository callbackRepository;

    private HttpServer httpServer;

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    @Test
    void dispatchesSignedWebhook_andAcceptsIdempotentCallback() throws Exception {
        BlockingQueue<CapturedRequest> requests = new LinkedBlockingQueue<>();
        startServer(202, requests);

        String signingSecret = "notif-secret-ack";
        String endpointResponse = createEndpoint(signingSecret, httpServerUrl(), 3);
        JsonNode endpointJson = objectMapper.readTree(endpointResponse);
        String endpointId = endpointJson.get("id").asText();
        assertThat(endpointJson.get("signingSecretMasked").asText()).isEqualTo(masked(signingSecret));
        assertThat(endpointRepository.findById(UUID.fromString(endpointId)).orElseThrow().getSigningSecret())
                .isNotEqualTo(signingSecret)
                .startsWith("enc:v1:");
        String paymentId = createPayment("notif-create-ack");

        mockMvc.perform(post("/api/webhooks/deliveries/dispatch")
                        .with(adminJwt())
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processedCount").value(1))
                .andExpect(jsonPath("$.succeededCount").value(1));

        CapturedRequest captured = requests.poll(5, TimeUnit.SECONDS);
        assertThat(captured).isNotNull();
        assertThat(captured.path()).isEqualTo("/hooks");
        assertThat(captured.headers().getFirst("X-LedgerForge-Event-Type")).isEqualTo("payment.created");
        assertThat(captured.headers().getFirst("X-LedgerForge-Signature")).startsWith("t=").contains(",v1=");
        JsonNode payload = objectMapper.readTree(captured.body());
        assertThat(payload.path("payment").path("idempotencyKey").asText()).isEqualTo(masked("notif-create-ack"));

        NotificationDeliveryEntity delivery = deliveryRepository.findByPaymentIdOrderByCreatedAtDesc(UUID.fromString(paymentId)).get(0);
        assertThat(captured.headers().getFirst("X-LedgerForge-Delivery-Id")).isEqualTo(delivery.getId().toString());
        assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.SUCCEEDED);
        assertThat(delivery.getReceiptStatus()).isEqualTo(NotificationReceiptStatus.PENDING);

        mockMvc.perform(get("/api/webhooks/endpoints")
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].signingSecretMasked").value(masked(signingSecret)));

        String callbackBody = """
                {
                  "deliveryId": "%s",
                  "status": "ACKNOWLEDGED",
                  "reason": "consumer accepted"
                }
                """.formatted(delivery.getId());

        mockMvc.perform(post("/api/webhooks/callbacks/{endpointId}", endpointId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-LedgerForge-Callback-Id", "cb-ack-1")
                        .header("X-LedgerForge-Signature", "t=1,v1=bad")
                        .content(callbackBody))
                .andExpect(status().isUnauthorized());

        String validSignature = callbackSignature(signingSecret, callbackBody, Instant.now());
        mockMvc.perform(post("/api/webhooks/callbacks/{endpointId}", endpointId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-LedgerForge-Callback-Id", "cb-ack-1")
                        .header("X-LedgerForge-Signature", validSignature)
                        .content(callbackBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(true))
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.deliveryStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.receiptStatus").value("ACKNOWLEDGED"));

        mockMvc.perform(post("/api/webhooks/callbacks/{endpointId}", endpointId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-LedgerForge-Callback-Id", "cb-ack-1")
                        .header("X-LedgerForge-Signature", validSignature)
                        .content(callbackBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(true))
                .andExpect(jsonPath("$.duplicate").value(true));

        NotificationDeliveryEntity acknowledged = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(acknowledged.getReceiptStatus()).isEqualTo(NotificationReceiptStatus.ACKNOWLEDGED);
        assertThat(callbackRepository.findByEndpointIdAndCallbackId(UUID.fromString(endpointId), "cb-ack-1")).isPresent();
    }

    @Test
    void failedDeliveries_retry_then_exhaust_to_failed() throws Exception {
        BlockingQueue<CapturedRequest> requests = new LinkedBlockingQueue<>();
        startServer(500, requests);

        createEndpoint("notif-secret-retry", httpServerUrl(), 2);
        String paymentId = createPayment("notif-create-retry");

        mockMvc.perform(post("/api/webhooks/deliveries/dispatch")
                        .with(adminJwt())
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processedCount").value(1))
                .andExpect(jsonPath("$.retryingCount").value(1))
                .andExpect(jsonPath("$.failedCount").value(0));

        NotificationDeliveryEntity firstAttempt = deliveryRepository.findByPaymentIdOrderByCreatedAtDesc(UUID.fromString(paymentId)).get(0);
        assertThat(firstAttempt.getStatus()).isEqualTo(NotificationDeliveryStatus.RETRY_PENDING);
        assertThat(firstAttempt.getAttemptCount()).isEqualTo(1);
        assertThat(firstAttempt.getLastResponseStatus()).isEqualTo(500);

        firstAttempt.setNextAttemptAt(Instant.now().minusSeconds(1));
        deliveryRepository.save(firstAttempt);

        mockMvc.perform(post("/api/webhooks/deliveries/dispatch")
                        .with(adminJwt())
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processedCount").value(1))
                .andExpect(jsonPath("$.failedCount").value(1))
                .andExpect(jsonPath("$.retryingCount").value(0));

        NotificationDeliveryEntity exhausted = deliveryRepository.findById(firstAttempt.getId()).orElseThrow();
        assertThat(exhausted.getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED);
        assertThat(exhausted.getAttemptCount()).isEqualTo(2);
        assertThat(requests).hasSize(2);
    }

    private String createEndpoint(String signingSecret, String url, int maxAttempts) throws Exception {
        return mockMvc.perform(post("/api/webhooks/endpoints")
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Local consumer",
                                  "url": "%s",
                                  "subscribedEvents": ["payment.*"],
                                  "maxAttempts": %s,
                                  "signingSecret": "%s"
                                }
                                """.formatted(url, maxAttempts, signingSecret)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String createPayment(String idempotencyKey) throws Exception {
        UUID payerId = createAccount("payer-" + idempotencyKey);
        UUID payeeId = createAccount("payee-" + idempotencyKey);

        CreatePaymentRequest request = new CreatePaymentRequest(payerId, payeeId, null, 12_500L, "USD", idempotencyKey);
        String response = mockMvc.perform(post("/api/payments")
                        .with(operatorJwt())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode json = objectMapper.readTree(response);
        return json.get("id").asText();
    }

    private UUID createAccount(String ownerId) {
        AccountEntity account = new AccountEntity();
        account.setOwnerId(ownerId);
        account.setCurrency("USD");
        account.setStatus(AccountStatus.ACTIVE);
        return accountRepository.save(account).getId();
    }

    private void startServer(int responseCode, BlockingQueue<CapturedRequest> requests) throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        httpServer.createContext("/hooks", exchange -> handle(exchange, responseCode, requests));
        httpServer.start();
    }

    private void handle(HttpExchange exchange, int responseCode, BlockingQueue<CapturedRequest> requests) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        requests.add(new CapturedRequest(exchange.getRequestURI().getPath(), exchange.getRequestHeaders(), new String(body, StandardCharsets.UTF_8)));
        byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(responseCode, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private String httpServerUrl() {
        return "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/hooks";
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor adminJwt() {
        return jwt().authorities(
                new SimpleGrantedAuthority("ROLE_ADMIN"),
                new SimpleGrantedAuthority("ROLE_OPERATOR"),
                new SimpleGrantedAuthority("ROLE_VIEWER")
        ).jwt(jwt -> jwt.claim("roles", List.of("ADMIN")));
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor operatorJwt() {
        return jwt().authorities(
                new SimpleGrantedAuthority("ROLE_OPERATOR"),
                new SimpleGrantedAuthority("ROLE_VIEWER")
        ).jwt(jwt -> jwt.claim("roles", List.of("OPERATOR")));
    }

    private String callbackSignature(String secret, String body, Instant timestamp) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String payload = timestamp.getEpochSecond() + "." + body;
        return "t=" + timestamp.getEpochSecond() + ",v1=" + HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private String masked(String value) {
        return value.length() <= 4 ? "****" : "****" + value.substring(value.length() - 4);
    }

    private record CapturedRequest(String path,
                                   com.sun.net.httpserver.Headers headers,
                                   String body) {
    }
}
