package com.react.global.exception;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.react.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ===== 정상 응답 =====

    @Test
    void 정상_응답은_data에_담긴다() throws Exception {
        mockMvc.perform(get("/api/v1/test/success"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(1))
                .andExpect(jsonPath("$.data.nickname").value("코딩왕"))
                .andExpect(jsonPath("$.data.rating").value(1000))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void 본문_없는_성공은_204를_반환한다() throws Exception {
        mockMvc.perform(delete("/api/v1/test/no-content"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    // ===== 비즈니스 오류 =====

    @Test
    void 이메일_중복은_409와_공통_오류_형식을_반환한다()
            throws Exception {

        mockMvc.perform(get("/api/v1/test/duplicate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("EMAIL_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.message")
                        .value("이미 가입된 이메일입니다."))
                .andExpect(jsonPath("$.details").value(nullValue()))
                .andExpect(standardErrorFields());
    }

    @Test
    void 없는_대상은_404를_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(standardErrorFields());
    }

    // ===== 요청값 오류 =====

    @Test
    void 요청값_검증_실패는_400을_반환한다() throws Exception {
        mockMvc.perform(
                        post("/api/v1/test/validation")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"nickname\":\"\"}")
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(standardErrorFields());
    }

    @Test
    void 잘못된_JSON은_400을_반환한다() throws Exception {
        // 의도적으로 유효하지 않은 JSON을 전달한다.
        String malformedJson = "{" + "invalid-json}";

        mockMvc.perform(
                        post("/api/v1/test/validation")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(malformedJson)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(standardErrorFields());
    }

    @Test
    void 필수_요청_파라미터가_없으면_400을_반환한다()
            throws Exception {

        mockMvc.perform(get("/api/v1/test/required-param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(standardErrorFields());
    }

    @Test
    void 잘못된_파라미터_타입은_400을_반환한다()
            throws Exception {

        mockMvc.perform(
                        get("/api/v1/test/number")
                                .param("count", "not-a-number")
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(standardErrorFields());
    }

    @Test
    void 필수_파일이_없으면_400을_반환한다() throws Exception {
        mockMvc.perform(
                        multipart(
                                HttpMethod.PATCH,
                                "/api/v1/test/profile-image"
                        )
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(standardErrorFields());
    }

    // ===== HTTP 프로토콜 오류 =====

    @Test
    void 지원하지_않는_HTTP_메서드는_405를_반환한다()
            throws Exception {

        mockMvc.perform(get("/api/v1/test/validation"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code")
                        .value("METHOD_NOT_ALLOWED"))
                .andExpect(standardErrorFields());
    }

    @Test
    void 지원하지_않는_ContentType은_415를_반환한다()
            throws Exception {

        mockMvc.perform(
                        post("/api/v1/test/validation")
                                .contentType(MediaType.TEXT_PLAIN)
                                .content("nickname=test")
                )
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code")
                        .value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(standardErrorFields());
    }

    // ===== 서버 오류 =====

    @Test
    void 예상하지_못한_예외는_500을_반환한다()
            throws Exception {

        mockMvc.perform(get("/api/v1/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(standardErrorFields());
    }

    @Test
    void 반환값_검증_실패는_500을_반환한다()
            throws Exception {

        mockMvc.perform(get("/api/v1/test/invalid-return"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(standardErrorFields())
                .andExpect(result -> {
                    HandlerMethodValidationException exception =
                            assertInstanceOf(
                                    HandlerMethodValidationException.class,
                                    result.getResolvedException()
                            );

                    assertTrue(exception.isForReturnValue());
                });
    }

    // ===== 명세의 에러 코드와 HTTP 상태 매핑 =====

    @ParameterizedTest
    @CsvSource({
            "INVALID_INPUT, 400",
            "INVALID_NICKNAME, 400",
            "INVALID_POLL_OPTION, 400",
            "UNAUTHENTICATED, 401",
            "INVALID_CREDENTIALS, 401",
            "FORBIDDEN, 403",
            "NOT_FOUND, 404",
            "ROOM_NOT_FOUND, 404",
            "INVALID_JOIN_CODE, 404",
            "POLL_NOT_FOUND, 404",
            "CONFLICT, 409",
            "EMAIL_ALREADY_EXISTS, 409",
            "INVALID_ROOM_STATUS, 409",
            "ROOM_NOT_LIVE, 409",
            "ROOM_ENDED, 409",
            "POLL_ALREADY_CLOSED, 409",
            "POLL_CLOSED, 409",
            "ALREADY_VOTED, 409",
            "ROOM_NOT_ENDED, 409",
            "REPORT_INPUT_NOT_READY, 409",
            "TOO_MANY_REQUESTS, 429",
            "INTERNAL_ERROR, 500",
            "LLM_REQUEST_FAILED, 503",
            "METHOD_NOT_ALLOWED, 405",
            "UNSUPPORTED_MEDIA_TYPE, 415"
    })
    void 에러코드별_HTTP_상태를_검증한다(
            String code,
            int expectedStatus
    ) throws Exception {

        mockMvc.perform(
                        get("/api/v1/test/errors/{code}", code)
                )
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(standardErrorFields());
    }

    // ===== 공통 JSON 필드 및 timestamp 검증 =====

    private ResultMatcher standardErrorFields() {
        return result -> {
            String body = result.getResponse().getContentAsString();
            JsonNode json = objectMapper.readTree(body);

            assertEquals(4, json.size(), body);

            assertTrue(json.has("code"), body);
            assertTrue(json.has("message"), body);
            assertTrue(json.has("details"), body);
            assertTrue(json.has("timestamp"), body);

            assertTrue(json.get("details").isNull(), body);
            assertFalse(json.has("error"), body);
            assertFalse(json.get("code").asText().isBlank(), body);
            assertFalse(json.get("message").asText().isBlank(), body);

            JsonNode timestamp = json.get("timestamp");
            assertTrue(timestamp.isTextual(), body);

            OffsetDateTime parsed = assertDoesNotThrow(
                    () -> OffsetDateTime.parse(timestamp.asText()),
                    body
            );

            assertEquals(ZoneOffset.UTC, parsed.getOffset());
        };
    }

    // ===== 테스트 전용 Controller =====

    @RestController
    @RequestMapping("/api/v1/test")
    static class TestController {

        @GetMapping("/success")
        ApiResponse<TestUserResponse> success() {
            return ApiResponse.of(
                    new TestUserResponse(1L, "코딩왕", 1000)
            );
        }

        @DeleteMapping("/no-content")
        ResponseEntity<Void> noContent() {
            return ResponseEntity.noContent().build();
        }

        @GetMapping("/duplicate")
        void duplicate() {
            throw new BusinessException(
                    ErrorCode.EMAIL_ALREADY_EXISTS
            );
        }

        @GetMapping("/not-found")
        void notFound() {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        @GetMapping("/errors/{code}")
        void businessError(
                @PathVariable("code") ErrorCode code
        ) {
            throw new BusinessException(code);
        }

        @PostMapping(
                value = "/validation",
                consumes = MediaType.APPLICATION_JSON_VALUE
        )
        void validation(
                @Valid @RequestBody TestRequest request
        ) {
        }

        @GetMapping("/required-param")
        void requiredParam(
                @RequestParam("keyword") String keyword
        ) {
        }

        @GetMapping("/number")
        void number(
                @RequestParam("count") int count
        ) {
        }

        @GetMapping("/unexpected")
        void unexpected() {
            throw new IllegalStateException("test error");
        }

        @GetMapping("/invalid-return")
        @Min(1)
        int invalidReturn() {
            return 0;
        }

        @PatchMapping(
                value = "/profile-image",
                consumes = MediaType.MULTIPART_FORM_DATA_VALUE
        )
        void uploadProfileImage(
                @RequestPart("image") MultipartFile image
        ) {
        }
    }

    record TestRequest(
            @NotBlank String nickname
    ) {
    }

    record TestUserResponse(
            Long userId,
            String nickname,
            Integer rating
    ) {
    }
}