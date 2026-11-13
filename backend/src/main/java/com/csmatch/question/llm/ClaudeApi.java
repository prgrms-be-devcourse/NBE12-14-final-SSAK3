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

/**
 * Anthropic Claude API 호출의 공통부. 문제 생성과 문제 검증이 같은 키와 클라이언트를 쓴다.
 * API 키가 없어도 서버는 기동하고, 호출할 때만 클라이언트를 만든다.
 */
@Slf4j
@Component
class ClaudeApi {

    private static final long MAX_TOKENS = 16000L;

    private final String apiKey;
    private volatile AnthropicClient client;

    ClaudeApi(@Value("${app.llm.api-key:}") String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    boolean isConfigured() {
        return !apiKey.isEmpty();
    }

    /**
     * 시스템 프롬프트와 사용자 메시지를 보내고 응답을 type 의 JSON 스키마로 받는다.
     * system 은 요청마다 같은 값이어야 프롬프트 캐시가 적용된다. logContext 는 로그에만 쓴다.
     *
     * @throws LlmException 호출이 실패했거나 응답을 쓸 수 없을 때. 인증 오류만 fatal 이다.
     */
    <T> T call(String model, String system, String user, Class<T> type, String logContext) {
        if (!isConfigured()) {
            throw new LlmException("LLM API 키가 설정되지 않았습니다.", true, null);
        }
        StructuredMessageCreateParams<T> params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(system)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()))
                .outputConfig(type)
                .addUserMessage(user)
                .build();

        StructuredMessage<T> response;
        try {
            response = client().messages().create(params);
        } catch (UnauthorizedException | PermissionDeniedException e) {
            // 키가 잘못됐거나 권한이 없으면 몇 번을 다시 해도 같다. 키 값은 로그에 남기지 않는다.
            throw new LlmException("LLM API 인증에 실패했습니다.", true, e);
        } catch (RuntimeException e) {
            throw new LlmException("LLM 호출에 실패했습니다: " + e.getClass().getSimpleName(), false, e);
        }

        log.info("LLM 호출 완료 model={} {} inputTokens={} outputTokens={}",
                model, logContext, response.usage().inputTokens(), response.usage().outputTokens());

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
                .orElseThrow(() -> new LlmException("LLM 응답이 비어 있습니다."));
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
