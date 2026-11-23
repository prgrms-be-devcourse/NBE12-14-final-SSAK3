package com.react.report.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.report.service.OpenAiReportOverviewClient;
import com.react.report.service.ReportOverviewClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Configuration
public class ReportOverviewConfiguration {
    @Bean
    ReportOverviewClient reportOverviewClient(ObjectMapper mapper,
            @Value("${app.ai.report.api-key}") String key, @Value("${app.ai.report.model}") String model)
            throws IOException {
        var transport = new SimpleClientHttpRequestFactory();
        transport.setConnectTimeout(Duration.ofSeconds(5));
        transport.setReadTimeout(Duration.ofSeconds(120));
        var http = RestClient.builder().requestFactory(transport).build();
        var prompt = new ClassPathResource("report/report-system-prompt.txt").getContentAsString(StandardCharsets.UTF_8);
        try (var schema = new ClassPathResource("report/report-draft.schema.json").getInputStream()) {
            return new OpenAiReportOverviewClient(http, mapper, key, model, prompt, mapper.readTree(schema));
        }
    }
}
