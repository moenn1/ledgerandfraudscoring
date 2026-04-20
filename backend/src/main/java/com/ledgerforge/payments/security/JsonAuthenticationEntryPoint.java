package com.ledgerforge.payments.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerforge.payments.audit.AuditService;
import com.ledgerforge.payments.common.api.ErrorResponse;
import com.ledgerforge.payments.common.web.CorrelationIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

@Component
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public JsonAuthenticationEntryPoint(ObjectMapper objectMapper, AuditService auditService) {
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        String correlationId = CorrelationIds.resolve(request);
        auditService.appendWithActor(
                "security.authentication.required",
                null,
                null,
                null,
                correlationId,
                "anonymous",
                "anonymous",
                Map.of(
                        "method", request.getMethod(),
                        "path", request.getRequestURI(),
                        "reason", authException.getMessage()
                )
        );
        writeError(response, HttpStatus.UNAUTHORIZED, "Authentication is required to access operator APIs", request.getRequestURI());
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String message, String path) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), new ErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                path
        ));
    }
}
