package com.react.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.ai.repository.AiAnalysisRepository;
import com.react.ai.repository.AiLlmCallUsageRepository;
import com.react.ai.repository.AudienceMessageRepository;
import com.react.ai.service.AiAnalysisJsonParser;
import com.react.ai.service.AiAnalysisCompletionService;
import com.react.ai.service.AudienceAnalysisLlmClient;
import com.react.ai.service.AudienceAnalysisService;
import com.react.ai.service.GeminiAudienceAnalysisLlmClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({GeminiAudienceAnalysisProperties.class, AudienceAnalysisRetryProperties.class,
        AudienceAnalysisCostProperties.class})
public class AiAnalysisConfiguration {

    @Bean
    RestClient geminiRestClient(GeminiAudienceAnalysisProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    @Bean
    AudienceAnalysisLlmClient audienceAnalysisLlmClient(RestClient geminiRestClient, ObjectMapper objectMapper,
                                                        GeminiAudienceAnalysisProperties properties) {
        return new GeminiAudienceAnalysisLlmClient(geminiRestClient, objectMapper, properties);
    }

    @Bean
    AiAnalysisJsonParser aiAnalysisJsonParser(ObjectMapper objectMapper) {
        return new AiAnalysisJsonParser(objectMapper);
    }

    @Bean
    AudienceAnalysisService audienceAnalysisService(AiAnalysisRepository analyses, AudienceMessageRepository messages,
                                                    AudienceAnalysisLlmClient llmClient,
                                                    AiAnalysisJsonParser jsonParser,
                                                    AudienceAnalysisRetryProperties retryProperties,
                                                    AiLlmCallUsageRepository usages,
                                                    AudienceAnalysisCostProperties costProperties,
                                                    AiAnalysisCompletionService completions) {
        return new AudienceAnalysisService(analyses, messages, llmClient, jsonParser, retryProperties,
                usages, costProperties, completions);
    }
}
