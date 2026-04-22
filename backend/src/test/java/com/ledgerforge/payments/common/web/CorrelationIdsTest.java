package com.ledgerforge.payments.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class CorrelationIdsTest {

    @Test
    void returnsProvidedHeaderWhenItIsSafe() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIds.HEADER, "corr-review-123/abc");

        assertThat(CorrelationIds.resolve(request)).isEqualTo("corr-review-123/abc");
    }

    @Test
    void trimsWhitespaceAroundSafeHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIds.HEADER, "  corr-review-123  ");

        assertThat(CorrelationIds.resolve(request)).isEqualTo("corr-review-123");
    }

    @Test
    void replacesUnsafeHeaderValues() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIds.HEADER, "corr-review-123\nsecond-line");

        assertGeneratedUuid(CorrelationIds.resolve(request));
    }

    @Test
    void replacesOverlongHeaderValues() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIds.HEADER, "x".repeat(129));

        assertGeneratedUuid(CorrelationIds.resolve(request));
    }

    @Test
    void generatesUuidWhenHeaderMissing() {
        assertGeneratedUuid(CorrelationIds.resolve(new MockHttpServletRequest()));
    }

    private void assertGeneratedUuid(String value) {
        assertThat(value).isNotBlank();
        assertThatCode(() -> UUID.fromString(value)).doesNotThrowAnyException();
    }
}
