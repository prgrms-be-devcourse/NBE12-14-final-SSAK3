package com.csmatch.question.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/** Anthropic Claude API 로 문제를 만든다. API 키가 없어도 서버는 기동하고, 호출할 때만 클라이언트를 만든다. */
@Slf4j
@Component
public class ClaudeQuestionGenerator implements QuestionGenerator {

    private static final String DEFAULT_MODEL = "claude-sonnet-5-5";
    private static final long MAX_TOKENS = 16000L;

    private final String apiKey;
    private final String model;
    private volatile AnthropicClient client;

    public ClaudeQuestionGenerator(@Value("${app.llm.api-key:}") String apiKey,
                                   @Value("${app.llm.model:" + DEFAULT_MODEL + "}") String model) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
    }

    @Override
    public String defaultModel() {
        return model;
    }

    @Override
    public boolean isConfigured() {
        return !apiKey.isEmpty();
    }

    @Override
    public GeneratedQuestion generate(QuestionRequest request) {
        if (!isConfigured()) {
            throw new LlmException("LLM API 키가 설정되지 않았습니다.", true, null);
        }
        StructuredMessageCreateParams<GeneratedQuestion> params = MessageCreateParams.builder()
                .model(request.model())
                .maxTokens(MAX_TOKENS)
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(QuestionPrompt.SYSTEM)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()))
                .outputConfig(GeneratedQuestion.class)
                .addUserMessage(QuestionPrompt.user(request))
                .build();

        StructuredMessage<GeneratedQuestion> response;
        try {
            response = client().messages().create(params);
        } catch (UnauthorizedException | PermissionDeniedException e) {
            // 키가 잘못됐거나 권한이 없으면 몇 번을 다시 해도 같다. 키 값은 로그에 남기지 않는다.
            throw new LlmException("LLM API 인증에 실패했습니다.", true, e);
        } catch (RuntimeException e) {
            throw new LlmException("LLM 호출에 실패했습니다: " + e.getClass().getSimpleName(), false, e);
        }

        log.info("LLM 호출 완료 model={} concept={} difficulty={} inputTokens={} outputTokens={}",
                request.model(), request.conceptName(), request.difficulty(),
                response.usage().inputTokens(), response.usage().outputTokens());

        if (response.stopReason().filter(reason -> reason.equals(StopReason.REFUSAL)).isPresent()) {
            throw new LlmException("LLM 이 응답을 거절했습니다.");
        }
        if (response.stopReason().filter(reason -> reason.equals(StopReason.MAX_TOKENS)).isPresent()) {
            throw new LlmException("LLM 응답이 길이 제한으로 잘렸습니다.");
        }
        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(typed -> typed.text())
                .findFirst()
                .orElseThrow(() -> new LlmException("LLM 응답에 문제가 없습니다."));
    }

    private AnthropicClient client() {
        AnthropicClient local = client;
        if (local == null) {
            synchronized (this) {
                if (client == null) {
                    client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
                }
                local = client;
            }
        }
        return local;
    }
}
