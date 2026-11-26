package com.react.report.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.react.report.repository.ReportAttemptRepository;
import com.react.report.service.BackgroundReportClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;

@RestController
public class OpenAiWebhookController {
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final int MAX_AGE_SECONDS = 300;
    private static final Set<String> TERMINAL = Set.of(
            "response.completed", "response.cancelled", "response.failed", "response.incomplete");

    private final ReportAttemptRepository attempts;
    private final ObjectReader json;
    private final SecretKeySpec signingKey;

    public OpenAiWebhookController(ReportAttemptRepository attempts, ObjectMapper mapper,
                                   @Value("${app.ai.report.webhook-secret:}") String secret,
                                   @Value("${app.ai.report.background-enabled:false}") boolean backgroundEnabled) {
        this.attempts = attempts;
        this.json = mapper.readerFor(JsonNode.class)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
        this.signingKey = decodeSecret(secret);
        if (backgroundEnabled && signingKey == null) {
            throw new IllegalStateException("Background reports require a valid OPENAI_REPORT_WEBHOOK_SECRET");
        }
    }

    @PostMapping("/api/v1/webhooks/openai")
    public ResponseEntity<Void> receive(HttpServletRequest request) throws IOException {
        if (signingKey == null) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        if (request.getContentLengthLong() > MAX_BODY_BYTES) return ResponseEntity.status(413).build();
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) return ResponseEntity.status(413).build();
        if (!verified(request, body)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();

        JsonNode event;
        try {
            event = json.readValue(body);
        } catch (IOException invalid) {
            return ResponseEntity.badRequest().build();
        }
        if (event == null || !event.path("type").isTextual()) return ResponseEntity.badRequest().build();
        if (TERMINAL.contains(event.get("type").textValue())) {
            String responseId = event.path("data").path("id").textValue();
            if (!BackgroundReportClient.validResponseId(responseId)) return ResponseEntity.badRequest().build();
            try {
                attempts.enqueueWebhook(responseId);
            } catch (DataAccessException unavailable) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            }
        }
        return ResponseEntity.noContent().build();
    }

    private boolean verified(HttpServletRequest request, byte[] body) {
        String id = request.getHeader("webhook-id");
        String timestamp = request.getHeader("webhook-timestamp");
        String signatures = request.getHeader("webhook-signature");
        if (id == null || id.isBlank() || id.length() > 255 || signatures == null || signatures.length() > 2048
                || timestamp == null || !timestamp.matches("[0-9]{1,12}")) return false;
        long seconds = Long.parseLong(timestamp);
        long now = Instant.now().getEpochSecond();
        if (seconds < now - MAX_AGE_SECONDS || seconds > now + MAX_AGE_SECONDS) return false;

        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(signingKey);
            hmac.update((id + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
            byte[] digest = hmac.doFinal(body);
            for (String signature : signatures.split(" ")) {
                if (!signature.startsWith("v1,")) continue;
                try {
                    if (MessageDigest.isEqual(digest, Base64.getDecoder().decode(signature.substring(3)))) return true;
                } catch (IllegalArgumentException ignored) {
                    // 키 회전 동안 뒤에 제공된 다른 v1 서명도 검사한다.
                }
            }
            return false;
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("Webhook signature verification unavailable");
        }
    }

    private static SecretKeySpec decodeSecret(String secret) {
        if (!secret.startsWith("whsec_")) return null;
        try {
            byte[] key = Base64.getDecoder().decode(secret.substring(6));
            if (key.length == 0) return null;
            return new SecretKeySpec(key, "HmacSHA256");
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
