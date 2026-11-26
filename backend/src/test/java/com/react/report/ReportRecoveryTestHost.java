package com.react.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Sends the same session-authenticated requests as the instructor's report screen. */
final class ReportRecoveryTestHost implements AutoCloseable {
    private final URI origin;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

    private ReportRecoveryTestHost(int port, ObjectMapper json) {
        this.origin = URI.create("http://127.0.0.1:" + port);
        this.json = json;
    }

    static ReportRecoveryTestHost login(int port, String email, String password, ObjectMapper json)
            throws IOException, InterruptedException {
        var host = new ReportRecoveryTestHost(port, json);
        try {
            String credentials = json.writeValueAsString(Map.of("email", email, "password", password));
            var request = host.withCsrf("/api/v1/auth/login").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(credentials));
            host.send(request, 200);
            return host;
        } catch (IOException | InterruptedException | RuntimeException | AssertionError failure) {
            host.close();
            throw failure;
        }
    }

    JsonNode status(long room) throws IOException, InterruptedException {
        return send(request("/api/v1/rooms/" + room + "/reports/status").GET(), 200);
    }

    JsonNode retry(long room, int expectedHttpStatus) throws IOException, InterruptedException {
        var request = withCsrf("/api/v1/rooms/" + room + "/reports/retry").POST(HttpRequest.BodyPublishers.noBody());
        return send(request, expectedHttpStatus);
    }

    private HttpRequest.Builder withCsrf(String path) throws IOException, InterruptedException {
        var csrf = send(request("/api/v1/auth/csrf").GET(), 200);
        return request(path).header(csrf.required("headerName").asText(), csrf.required("token").asText());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(10));
    }

    private JsonNode send(HttpRequest.Builder builder, int expectedStatus) throws IOException, InterruptedException {
        var request = builder.build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s %s", request.method(), request.uri().getPath()).isEqualTo(expectedStatus);
        return json.readTree(response.body()).required("data");
    }

    @Override
    public void close() {
        http.close();
    }
}
