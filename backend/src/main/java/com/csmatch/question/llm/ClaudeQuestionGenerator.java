package com.csmatch.question.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Anthropic Claude API 로 문제를 만든다. */
@Component
public class ClaudeQuestionGenerator implements QuestionGenerator {

    private static final String DEFAULT_MODEL = "claude-sonnet-5-5";

    private final ClaudeApi api;
    private final String model;

    public ClaudeQuestionGenerator(ClaudeApi api, @Value("${app.llm.model:" + DEFAULT_MODEL + "}") String model) {
        this.api = api;
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
    }

    @Override
    public String defaultModel() {
        return model;
    }

    @Override
    public boolean isConfigured() {
        return api.isConfigured();
    }

    @Override
    public GeneratedQuestion generate(QuestionRequest request) {
        return api.call(request.model(), QuestionPrompt.SYSTEM, QuestionPrompt.user(request),
                GeneratedQuestion.class,
                "concept=" + request.conceptName() + " difficulty=" + request.difficulty());
    }
}
