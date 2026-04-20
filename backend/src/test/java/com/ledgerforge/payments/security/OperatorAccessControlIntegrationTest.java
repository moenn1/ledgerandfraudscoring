package com.ledgerforge.payments.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerforge.payments.account.AccountEntity;
import com.ledgerforge.payments.account.AccountService;
import com.ledgerforge.payments.account.api.CreateAccountRequest;
import com.ledgerforge.payments.audit.AuditEventEntity;
import com.ledgerforge.payments.audit.AuditEventRepository;
import com.ledgerforge.payments.fraud.ReviewCaseEntity;
import com.ledgerforge.payments.fraud.ReviewCaseRepository;
import com.ledgerforge.payments.payment.PaymentIntentEntity;
import com.ledgerforge.payments.payment.PaymentService;
import com.ledgerforge.payments.payment.api.ConfirmPaymentRequest;
import com.ledgerforge.payments.payment.api.CreatePaymentRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OperatorAccessControlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountService accountService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private ReviewCaseRepository reviewCaseRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Test
    void unauthenticatedRequest_returns401_andWritesAuditEvent() throws Exception {
        mockMvc.perform(get("/api/payments"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication is required to access operator APIs"));

        AuditEventEntity event = latestAuditEvent("security.authentication.required");
        assertThat(event.getActorType()).isEqualTo("anonymous");
        assertThat(event.getActorId()).isEqualTo("anonymous");
    }

    @Test
    @WithMockUser(username = "viewer.one@ledgerforge.local", roles = "VIEWER")
    void viewerCannotCreatePayments() throws Exception {
        AccountEntity payer = accountService.create(new CreateAccountRequest("viewer-payer", "USD", null));
        AccountEntity payee = accountService.create(new CreateAccountRequest("viewer-payee", "USD", null));

        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "viewer-create-1")
                        .content(objectMapper.writeValueAsString(
                                new CreatePaymentRequest(payer.getId(), payee.getId(), null, 500L, "USD", "viewer-create-1")
                        )))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Authenticated operator is not permitted to perform this action"));

        AuditEventEntity event = latestAuditEvent("security.authorization.denied");
        assertThat(event.getActorType()).isEqualTo("operator");
        assertThat(event.getActorId()).isEqualTo("viewer.one@ledgerforge.local");
    }

    @Test
    @WithMockUser(username = "risk.reviewer@ledgerforge.local", roles = "REVIEWER")
    void reviewDecisionUsesAuthenticatedActorForAudit() throws Exception {
        AccountEntity payer = accountService.create(new CreateAccountRequest("review-payer", "USD", null));
        AccountEntity payee = accountService.create(new CreateAccountRequest("review-payee", "USD", null));
        PaymentIntentEntity payment = paymentService.createWithIdempotency(
                new CreatePaymentRequest(payer.getId(), payee.getId(), null, 150_000L, "USD", "review-auth-create"),
                "review-auth-create",
                "review-auth-correlation-create"
        );
        paymentService.confirm(
                payment.getId(),
                new ConfirmPaymentRequest(true, "US", "CA", 0, 15),
                "review-auth-confirm",
                "review-auth-correlation-confirm"
        );

        ReviewCaseEntity reviewCase = reviewCaseRepository.findByPaymentId(payment.getId()).orElseThrow();

        mockMvc.perform(post("/api/fraud/reviews/{id}/decision", reviewCase.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "decision": "APPROVE",
                                  "note": "approved with reviewer role"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        AuditEventEntity event = latestAuditEvent("fraud.review_case.decided");
        assertThat(event.getActorType()).isEqualTo("operator");
        assertThat(event.getActorId()).isEqualTo("risk.reviewer@ledgerforge.local");
        assertThat(event.getDetailsJson()).contains("risk.reviewer@ledgerforge.local");
    }

    private AuditEventEntity latestAuditEvent(String eventType) {
        return auditEventRepository.findAll().stream()
                .filter(event -> eventType.equals(event.getEventType()))
                .max(Comparator.comparing(AuditEventEntity::getCreatedAt))
                .orElseThrow();
    }
}
