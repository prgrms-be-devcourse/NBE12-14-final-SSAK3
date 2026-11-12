package com.csmatch.global.exception;

import com.csmatch.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 정상_응답은_data에_담긴다() throws Exception {
        mockMvc.perform(get("/api/v1/test/success"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname")
                        .value("코딩왕"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void 이메일_중복은_409를_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/test/duplicate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code")
                        .value("EMAIL_DUPLICATED"))
                .andExpect(jsonPath("$.error.message")
                        .value("이미 가입된 이메일입니다."));
    }

    @Test
    void 요청값_검증_실패는_400을_반환한다() throws Exception {
        mockMvc.perform(post("/api/v1/test/validation")
                        .contentType("application/json")
                        .content("{\"nickname\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("VALIDATION_FAILED"));
    }

    @Test
    void 잘못된_JSON은_400을_반환한다() throws Exception {
        mockMvc.perform(post("/api/v1/test/validation")
                        .contentType("application/json")
                        .content("{invalid-json}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("VALIDATION_FAILED"));
    }

    @Test
    void 없는_대상은_404를_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code")
                        .value("NOT_FOUND"));
    }

    @Test
    void 예상하지_못한_오류는_500을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code")
                        .value("INTERNAL_ERROR"));
    }

    @Test
    void 본문_없는_성공은_204를_반환한다() throws Exception {
        mockMvc.perform(delete("/api/v1/test/no-content"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    void 지원하지_않는_HTTP_메서드는_405를_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/test/validation"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code")
                        .value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void 지원하지_않는_ContentType은_415를_반환한다() throws Exception {
        mockMvc.perform(post("/api/v1/test/validation")
                        .contentType("text/plain")
                        .content("nickname=test"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code")
                        .value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void 필수_이미지_파일이_없으면_400을_반환한다() throws Exception {
        mockMvc.perform(
                        multipart(HttpMethod.PATCH, "/api/v1/test/profile-image")
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("VALIDATION_FAILED"));
    }

    @Test
    void 필수_요청_파라미터가_없으면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/test/required-param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("VALIDATION_FAILED"));
    }

    @Test
    void 반환값_검증_실패는_500을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/test/invalid-return"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code")
                        .value("INTERNAL_ERROR"))
                .andExpect(result -> {
                    HandlerMethodValidationException exception =
                            assertInstanceOf(
                                    HandlerMethodValidationException.class,
                                    result.getResolvedException()
                            );

                    assertTrue(exception.isForReturnValue());
                });
    }


    @RestController
    @RequestMapping("/api/v1/test")
    static class TestController {

        @GetMapping("/success")
        ApiResponse<TestUserResponse> success() {
            return ApiResponse.of(
                    new TestUserResponse(1L, "코딩왕", 1000)
            );
        }

        @GetMapping("/duplicate")
        void duplicate() {
            throw new BusinessException(
                    ErrorCode.EMAIL_DUPLICATED
            );
        }

        @GetMapping("/required-param")
        void requiredParam(@RequestParam("keyword") String keyword) {
        }

        @GetMapping("/invalid-return")
        @Min(1)
        int invalidReturn() {
            return 0;
        }

        @PostMapping(
                value = "/validation",
                consumes = "application/json"
        )
        void validation(@Valid @RequestBody TestRequest request) {
        }

        @GetMapping("/not-found")
        void notFound() {
            throw new BusinessException(
                    ErrorCode.NOT_FOUND
            );
        }

        @GetMapping("/unexpected")
        void unexpected() {
            throw new IllegalStateException("test error");
        }


        @DeleteMapping("/no-content")
        ResponseEntity<Void> noContent() {
            return ResponseEntity.noContent().build();
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
