package com.react.report.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.ReActApplication;
import com.react.report.service.OpenAiReportOverviewClient;
import com.react.report.service.ReportOverviewClient;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Runs the normal room-end workflow against isolated local services and a timed Responses API stub. */
@TestConfiguration(proxyBeanMethods = false)
@Profile("report-load")
public class ReportLoadServer {
    public static void main(String[] args) throws IOException {
        var readyFile = System.getenv("REPORT_LOAD_READY_FILE");
        var mysqlPort = port("REPORT_LOAD_MYSQL_PORT", 18643);
        var redisPort = port("REPORT_LOAD_REDIS_PORT", 18644);
        var context = new SpringApplicationBuilder(ReActApplication.class, ReportLoadServer.class).run(
                "--spring.config.location=classpath:/application.yml", "--spring.profiles.active=report-load",
                "--server.address=127.0.0.1", "--server.port=" + (readyFile == null ? 18640 : 0),
                "--server.servlet.session.cookie.secure=false",
                "--spring.datasource.url=jdbc:mysql://127.0.0.1:" + mysqlPort + "/report_load?serverTimezone=Asia/Seoul&allowPublicKeyRetrieval=true&useSSL=false",
                "--spring.datasource.username=root", "--spring.datasource.password=local-only-report",
                "--spring.data.redis.host=127.0.0.1", "--spring.data.redis.port=" + redisPort,
                "--PARTICIPANT_JOIN_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                "--app.ai.report.api-key=", "--app.ai.report.background-enabled=false",
                "--app.ai.report.alert.webhook-url=", "--app.ai.report.webhook-secret=",
                "--app.ai.audience.gemini.api-key=", "--app.ai.audience.gemini.base-url=" + mockOrigin());
        if (readyFile != null) {
            try {
                int boundPort = ((WebServerApplicationContext) context).getWebServer().getPort();
                publishPort(Path.of(readyFile), boundPort);
            } catch (IOException | RuntimeException e) {
                context.close();
                throw e;
            }
        }
    }

    private static int port(String variable, int fallback) {
        String value = System.getenv(variable);
        int port = value == null ? fallback : Integer.parseInt(value);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid port for " + variable);
        return port;
    }

    private static URI mockOrigin() {
        return URI.create("http://127.0.0.1:" + port("REPORT_LOAD_MOCK_PORT", 18641));
    }

    private static void publishPort(Path readyFile, int port) throws IOException {
        Path temp = Files.createTempFile(readyFile.toAbsolutePath().getParent(), "server-port-", ".tmp");
        try {
            Files.writeString(temp, Integer.toString(port), StandardCharsets.US_ASCII);
            Files.move(temp, readyFile, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Bean
    @Primary
    ReportOverviewClient localReportClient(ObjectMapper mapper) throws IOException {
        var transport = ReportOverviewConfiguration.transport();
        var endpoint = mockOrigin().resolve("/v1/responses");
        var http = RestClient.builder().requestFactory((uri, method) -> transport.createRequest(endpoint, method)).build();
        var prompt = new ClassPathResource("report/report-system-prompt.txt").getContentAsString(StandardCharsets.UTF_8);
        try (var schema = new ClassPathResource("report/report-draft.schema.json").getInputStream()) {
            return new OpenAiReportOverviewClient(http, mapper, "fixture-key", "fixture-model", prompt, mapper.readTree(schema));
        }
    }
}
