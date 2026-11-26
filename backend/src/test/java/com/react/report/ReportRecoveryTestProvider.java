package com.react.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** The provider lives in the test JVM so its accepted response survives both backend processes. */
final class ReportRecoveryTestProvider implements AutoCloseable {
    static final String RESPONSE_ID = "resp_recovery_test_original";
    static final String MARKER = "서버가 종료된 동안 완성한 원본 요약";
    static final String RETRY_RESPONSE_ID = "resp_recovery_test_retry";
    static final String RETRY_MARKER = "강사가 다시 요청해서 완성한 요약";
    private static final String RESPONSES = "/v1/responses";

    private record Response(String id, JsonNode metadata, String draft) {}

    private final ObjectMapper json = new ObjectMapper();
    private final HttpServer server;
    private final AtomicInteger creates = new AtomicInteger();
    private final AtomicInteger completedReads = new AtomicInteger();
    private final Map<String, Response> responsesByPath = new ConcurrentHashMap<>();
    private volatile boolean completed;

    ReportRecoveryTestProvider() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(RESPONSES, this::handle);
        server.start();
    }

    URI origin() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    void complete() {
        completed = true;
    }

    int createCount() {
        return creates.get();
    }

    int completedReadCount() {
        return completedReads.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.equals(RESPONSES) && method.equals("POST")) {
                submit(exchange);
                return;
            }
            var response = responsesByPath.get(path);
            if (response == null) {
                exchange.sendResponseHeaders(404, -1);
            } else if (method.equals("GET")) {
                if (completed) {
                    completedReads.incrementAndGet();
                    var message = Map.of("type", "message", "role", "assistant", "content",
                            List.of(Map.of("type", "output_text", "text", response.draft())));
                    sendResponse(exchange, response, "completed", List.of(message));
                } else {
                    sendResponse(exchange, response, "in_progress", List.of());
                }
            } else if (method.equals("DELETE")) {
                responsesByPath.remove(path);
                sendJson(exchange, Map.of("object", "response.deleted", "id", response.id(), "deleted", true));
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }

    private void submit(HttpExchange exchange) throws IOException {
        int ordinal = creates.incrementAndGet();
        if (ordinal > 2) {
            exchange.sendResponseHeaders(409, -1);
            return;
        }
        var request = json.readTree(exchange.getRequestBody());
        if (!request.path("background").asBoolean() || !request.path("store").asBoolean()
                || request.path("metadata").path("report_attempt").asText().isBlank()) {
            exchange.sendResponseHeaders(400, -1);
            return;
        }
        String id = ordinal == 1 ? RESPONSE_ID : RETRY_RESPONSE_ID;
        String marker = ordinal == 1 ? MARKER : RETRY_MARKER;
        var input = json.readTree(request.path("input").textValue());
        for (var record : input.path("evidenceRecords")) {
            if ("CHAT_MESSAGE".equals(record.path("type").asText())) {
                var interest = Map.of("text", marker, "perspectives", List.of(),
                        "relatedWindows", record.get("relatedWindows"),
                        "evidence", List.of(Map.of("type", "CHAT_MESSAGE", "id", record.get("id"))));
                var draft = json.writeValueAsString(Map.of(
                        "overview", Map.of("mainInterests", List.of(interest), "reviewPoints", List.of(), "suggestions", List.of()),
                        "followUp", Map.of("reviewedChatIds", List.of(record.get("id")), "groups", List.of())));
                var response = new Response(id, request.get("metadata"), draft);
                responsesByPath.put(RESPONSES + "/" + id, response);
                sendResponse(exchange, response, "queued", List.of());
                return;
            }
        }
        exchange.sendResponseHeaders(400, -1);
    }

    private void sendResponse(HttpExchange exchange, Response response, String status, List<?> output) throws IOException {
        sendJson(exchange, Map.of("object", "response", "id", response.id(), "status", status,
                "metadata", response.metadata(), "output", output));
    }

    private void sendJson(HttpExchange exchange, Map<String, ?> payload) throws IOException {
        byte[] body = json.writeValueAsBytes(payload);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
