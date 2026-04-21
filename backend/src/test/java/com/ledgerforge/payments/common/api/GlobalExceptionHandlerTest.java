package com.ledgerforge.payments.common.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void unexpectedExceptionsReturnSanitizedMessage() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/payments/test");

        ErrorResponse response = handler.handleUnexpected(
                        new IllegalStateException("database password leaked"),
                        request
                )
                .getBody();

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(500);
        assertThat(response.message()).isEqualTo("An unexpected error occurred");
        assertThat(response.path()).isEqualTo("/api/payments/test");
    }
}
