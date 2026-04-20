package com.ledgerforge.payments.account;

import com.ledgerforge.payments.audit.AuditEventEntity;
import com.ledgerforge.payments.audit.AuditEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AccountControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Test
    void updateStatus_freezesAndReactivatesAccount() throws Exception {
        UUID accountId = createAccount("frozen-owner", "USD");

        mockMvc.perform(post("/api/accounts/{id}/status", accountId)
                        .header("X-Correlation-Id", "account-freeze-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "FROZEN",
                                  "reason": "manual review escalation"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));

        mockMvc.perform(post("/api/accounts/{id}/status", accountId)
                        .header("X-Correlation-Id", "account-unfreeze-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "ACTIVE",
                                  "reason": "false positive cleared"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        List<AuditEventEntity> events = auditEventRepository.findAll().stream()
                .filter(event -> accountId.equals(event.getAccountId()))
                .sorted(Comparator.comparing(AuditEventEntity::getCreatedAt))
                .toList();

        assertThat(events).hasSize(2);
        assertThat(events.get(0).getEventType()).isEqualTo("account.status_changed");
        assertThat(events.get(0).getCorrelationId()).isEqualTo("account-freeze-1");
        assertThat(events.get(0).getDetailsJson()).contains("\"previousStatus\":\"ACTIVE\"");
        assertThat(events.get(0).getDetailsJson()).contains("\"status\":\"FROZEN\"");
        assertThat(events.get(0).getDetailsJson()).contains("\"reason\":\"manual review escalation\"");

        assertThat(events.get(1).getEventType()).isEqualTo("account.status_changed");
        assertThat(events.get(1).getCorrelationId()).isEqualTo("account-unfreeze-1");
        assertThat(events.get(1).getDetailsJson()).contains("\"previousStatus\":\"FROZEN\"");
        assertThat(events.get(1).getDetailsJson()).contains("\"status\":\"ACTIVE\"");
        assertThat(events.get(1).getDetailsJson()).contains("\"reason\":\"false positive cleared\"");
    }

    private UUID createAccount(String ownerId, String currency) {
        AccountEntity account = new AccountEntity();
        account.setOwnerId(ownerId);
        account.setCurrency(currency);
        account.setStatus(AccountStatus.ACTIVE);
        return accountRepository.save(account).getId();
    }
}
