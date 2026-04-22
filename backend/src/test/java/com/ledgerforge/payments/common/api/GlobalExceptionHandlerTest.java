package com.ledgerforge.payments.common.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.lang.reflect.Method;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void unexpectedErrorsReturnGenericMessage() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/payments");

        ResponseEntity<ErrorResponse> response = handler.handleUnexpected(
                new IllegalStateException("jdbc:postgresql://secret-host/ledgerforge"),
                request
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Internal server error");
        assertThat(response.getBody().message()).doesNotContain("secret-host");
    }

    @Test
    void beanValidationErrorsReturnFieldNamesInsteadOfRawMessages() throws Exception {
        Method method = ValidationController.class.getDeclaredMethod("submit", ValidationPayload.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new ValidationPayload(), "payload");
        bindingResult.addError(new FieldError("payload", "actor", "", false, null, null, "must not be blank"));

        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(parameter, bindingResult);
        HttpServletRequest request = new MockHttpServletRequest("POST", "/api/fraud/reviews/id/decision");

        ResponseEntity<ErrorResponse> response = handler.handleValidation(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Request validation failed for fields: actor");
        assertThat(response.getBody().message()).doesNotContain("must not be blank");
    }

    @Test
    void constraintViolationsReturnPropertyNames() {
        @SuppressWarnings("unchecked")
        ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
        Path path = mock(Path.class);
        when(path.toString()).thenReturn("decide.actor");
        when(violation.getPropertyPath()).thenReturn(path);
        ConstraintViolationException exception = new ConstraintViolationException(Set.of(violation));
        HttpServletRequest request = new MockHttpServletRequest("POST", "/api/fraud/reviews/id/decision");

        ResponseEntity<ErrorResponse> response = handler.handleConstraintViolation(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Request validation failed for fields: decide.actor");
    }

    private static final class ValidationController {
        @SuppressWarnings("unused")
        void submit(ValidationPayload payload) {
        }
    }

    private static final class ValidationPayload {
    }
}
