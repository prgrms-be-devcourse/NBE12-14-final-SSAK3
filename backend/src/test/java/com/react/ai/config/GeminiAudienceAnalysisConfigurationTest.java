package com.react.ai.config;

import com.react.ReActApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ReActApplication.class, properties = {
        "GEMINI_API_KEY=test-key",
        "GEMINI_MODEL=gemini-3.5-flash-lite"
})
@ActiveProfiles("test")
class GeminiAudienceAnalysisConfigurationTest {
    @Autowired
    GeminiAudienceAnalysisProperties properties;

    @Test
    void Gemini_환경변수를_분석_설정으로_주입한다() {
        assertThat(properties.apiKey()).isEqualTo("test-key");
        assertThat(properties.model()).isEqualTo("gemini-3.5-flash-lite");
    }
}
