package com.ledgerforge.payments.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerforge.payments.account.api.CreateAccountRequest;
import com.ledgerforge.payments.ledger.CreateJournalRequest;
import com.ledgerforge.payments.ledger.CreateLedgerLegRequest;
import com.ledgerforge.payments.ledger.JournalType;
import com.ledgerforge.payments.ledger.LedgerDirection;
import com.ledgerforge.payments.ledger.LedgerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser(roles = "ADMIN")
class AccountControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountService accountService;

    @Autowired
    private LedgerService ledgerService;

    @Test
    void createAccount_returnsSupportedCurrencies() throws Exception {
        String response = mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerId": "multi-owner",
                                  "currency": "USD",
                                  "supportedCurrencies": ["EUR", "USD"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.supportedCurrencies[0]").value("EUR"))
                .andExpect(jsonPath("$.supportedCurrencies[1]").value("USD"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String accountId = objectMapper.readTree(response).get("id").asText();

        mockMvc.perform(get("/api/accounts/{id}", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.supportedCurrencies[0]").value("EUR"))
                .andExpect(jsonPath("$.supportedCurrencies[1]").value("USD"));
    }

    @Test
    void updateStatus_freezesAndReactivatesAccount() throws Exception {
        String response = mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerId": "frozen-owner",
                                  "currency": "USD"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String accountId = objectMapper.readTree(response).get("id").asText();

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
                                  "reason": "case cleared"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void multiCurrencyBalanceAndReplayRequireExplicitCurrency() throws Exception {
        AccountEntity multiCurrencyAccount = accountService.create(
                new CreateAccountRequest("wallet-owner", "USD", List.of("EUR", "USD"))
        );
        AccountEntity eurCounterparty = accountService.create(new CreateAccountRequest("counterparty-eur", "EUR", null));
        AccountEntity usdCounterparty = accountService.create(new CreateAccountRequest("counterparty-usd", "USD", null));

        ledgerService.createJournal(new CreateJournalRequest(
                JournalType.PAYMENT,
                "wallet-eur-leg",
                List.of(
                        new CreateLedgerLegRequest(multiCurrencyAccount.getId(), LedgerDirection.DEBIT, new BigDecimal("15.00"), "EUR"),
                        new CreateLedgerLegRequest(eurCounterparty.getId(), LedgerDirection.CREDIT, new BigDecimal("15.00"), "EUR")
                )
        ));
        ledgerService.createJournal(new CreateJournalRequest(
                JournalType.PAYMENT,
                "wallet-usd-leg",
                List.of(
                        new CreateLedgerLegRequest(multiCurrencyAccount.getId(), LedgerDirection.DEBIT, new BigDecimal("7.00"), "USD"),
                        new CreateLedgerLegRequest(usdCounterparty.getId(), LedgerDirection.CREDIT, new BigDecimal("7.00"), "USD")
                )
        ));

        mockMvc.perform(get("/api/accounts/{id}/balance", multiCurrencyAccount.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Currency is required when querying balances for a multi-currency account"));

        String eurBalanceResponse = mockMvc.perform(get("/api/accounts/{id}/balance", multiCurrencyAccount.getId())
                        .queryParam("currency", "EUR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode eurBalanceJson = objectMapper.readTree(eurBalanceResponse);
        assertThat(eurBalanceJson.get("balance").decimalValue()).isEqualByComparingTo("-15.0000");

        mockMvc.perform(get("/api/accounts/{id}/ledger", multiCurrencyAccount.getId())
                        .queryParam("currency", "EUR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].currency").value("EUR"));

        mockMvc.perform(get("/api/ledger/replay/accounts/{accountId}", multiCurrencyAccount.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Currency is required when replaying balances for a multi-currency account"));

        String usdReplayResponse = mockMvc.perform(get("/api/ledger/replay/accounts/{accountId}", multiCurrencyAccount.getId())
                        .queryParam("currency", "USD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.entryCount").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode usdReplayJson = objectMapper.readTree(usdReplayResponse);
        assertThat(usdReplayJson.get("projectedBalance").decimalValue()).isEqualByComparingTo("-7.0000");
    }
}
