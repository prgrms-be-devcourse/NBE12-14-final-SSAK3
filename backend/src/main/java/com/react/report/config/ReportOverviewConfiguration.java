package com.react.report.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.report.service.BackgroundReportClient;
import com.react.report.service.OpenAiReportOverviewClient;
import com.react.report.service.ReportOverviewClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class ReportOverviewConfiguration {
    private final OpenAiReportOverviewClient gateway;

    public ReportOverviewConfiguration(ObjectMapper mapper,
            @Value("${app.ai.report.api-key}") String key, @Value("${app.ai.report.model}") String model)
            throws IOException {
        var http = RestClient.builder().requestFactory(transport()).build();
        var prompt = new ClassPathResource("report/report-system-prompt.txt").getContentAsString(StandardCharsets.UTF_8);
        try (var schema = new ClassPathResource("report/report-draft.schema.json").getInputStream()) {
            gateway = new OpenAiReportOverviewClient(http, mapper, key, model, prompt, mapper.readTree(schema));
        }
    }

    static SimpleClientHttpRequestFactory transport() {
        // Spring의 streaming POST는 응답 헤더 유실 때에도 JDK가 이미 전송한 본문을 재전송하지 않는다.
        var transport = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        transport.setConnectTimeout(Duration.ofSeconds(5));
        transport.setReadTimeout(Duration.ofSeconds(120));
        return transport;
    }

    @Bean
    ReportOverviewClient reportOverviewClient() {
        return gateway;
    }

    @Bean
    BackgroundReportClient backgroundReportClient() {
        return gateway.background();
    }
}
