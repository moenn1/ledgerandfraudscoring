package com.ledgerforge.payments.idempotency;

import com.ledgerforge.payments.common.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class IdempotencyService {

    private final IdempotencyRecordRepository repository;

    public IdempotencyService(IdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public boolean isReplay(String scope, String idempotencyKey, String requestFingerprint) {
        Optional<IdempotencyRecordEntity> existing = repository.findByScopeAndIdempotencyKey(scope, idempotencyKey);
        if (existing.isEmpty()) {
            return false;
        }
        if (!existing.get().getRequestFingerprint().equals(requestFingerprint)) {
            throw new ApiException(HttpStatus.CONFLICT, "Idempotency key reused with different payload");
        }
        return true;
    }

    @Transactional
    public void record(String scope, String idempotencyKey, String requestFingerprint, String responsePayload) {
        if (repository.findByScopeAndIdempotencyKey(scope, idempotencyKey).isPresent()) {
            return;
        }

        IdempotencyRecordEntity record = new IdempotencyRecordEntity();
        record.setScope(scope);
        record.setIdempotencyKey(idempotencyKey);
        record.setRequestFingerprint(requestFingerprint);
        record.setResponseHash(sha256(responsePayload));
        repository.save(record);
    }

    public String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
