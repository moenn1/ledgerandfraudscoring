package com.ledgerforge.payments.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerforge.payments.audit.AuditService;
import com.ledgerforge.payments.common.api.ErrorResponse;
import com.ledgerforge.payments.common.web.CorrelationIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

@Component
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public JsonAccessDeniedHandler(ObjectMapper objectMapper, AuditService auditService) {
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        OperatorIdentity operator = OperatorIdentity.from(authentication);
        String correlationId = CorrelationIds.resolve(request);
        auditService.appendWithActor(
                "security.authorization.denied",
                null,
                null,
                null,
                correlationId,
                "operator",
                operator.actorId(),
                Map.of(
                        "method", request.getMethod(),
                        "path", request.getRequestURI(),
                        "roles", operator.roles(),
                        "reason", accessDeniedException.getMessage()
                )
        );
        writeError(response, HttpStatus.FORBIDDEN, "Authenticated operator is not permitted to perform this action", request.getRequestURI());
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
