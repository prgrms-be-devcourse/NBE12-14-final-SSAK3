package com.csmatch.admin.service;

import com.csmatch.admin.dto.QuestionGenerationDto;
import com.csmatch.global.exception.BusinessException;
import com.csmatch.global.exception.ErrorCode;
import com.csmatch.question.Enum.Difficulty;
import com.csmatch.question.dto.ConceptSource;
import com.csmatch.question.llm.GeneratedQuestion;
import com.csmatch.question.llm.LlmException;
import com.csmatch.question.llm.QuestionGenerator;
import com.csmatch.question.llm.QuestionRequest;
import com.csmatch.question.repository.CategoryRepository;
import com.csmatch.question.repository.ConceptRepository;
import com.csmatch.question.repository.QuestionRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 관리자가 시작한 AI 문제 생성을 백그라운드에서 한 번에 하나만 실행한다.
 * 진행 상태는 메모리에만 두므로 서버를 재시작하면 초기화된다.
 */
@Slf4j
@Service
public class QuestionGenerationService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int EXISTING_QUESTION_LIMIT = 10;
    private static final long SHUTDOWN_WAIT_SECONDS = 55;

    private final QuestionGenerator generator;
    private final ConceptRepository concepts;
    private final CategoryRepository categories;
    private final QuestionRepository questions;
    private final GeneratedQuestionSaver saver;
    private final Set<String> allowedModels;
    private final int maxCount;
    private final int maxAttempts;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean stopRequested;
    private volatile Run lastRun;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "question-generation");
        thread.setDaemon(true);
        return thread;
    });

    public QuestionGenerationService(QuestionGenerator generator, ConceptRepository concepts,
                                     CategoryRepository categories, QuestionRepository questions,
                                     GeneratedQuestionSaver saver,
                                     @Value("${app.llm.models:}") List<String> models,
                                     @Value("${app.llm.max-count:30}") int maxCount,
                                     @Value("${app.llm.max-attempts:3}") int maxAttempts) {
        this.generator = generator;
        this.concepts = concepts;
        this.categories = categories;
        this.questions = questions;
        this.saver = saver;
        Set<String> allowed = new LinkedHashSet<>();
        models.stream().map(String::trim).filter(model -> !model.isEmpty()).forEach(allowed::add);
        this.allowedModels = Set.copyOf(allowed);
        this.maxCount = maxCount;
        this.maxAttempts = maxAttempts;
    }

    /** 생성을 시작하고 바로 돌려준다. 검증 → 설정 확인 → 중복 실행 확인 순서로 막는다. */
    public QuestionGenerationDto.Status start(QuestionGenerationDto.StartRequest request) {
        int count = request.count();
        if (count < 1 || count > maxCount) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        Integer categoryId = request.categoryId();
        if (categoryId != null && !categories.existsById(categoryId)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        // 생략하면 기본 모델. 지정했는데 허용 목록에도 기본 모델에도 없으면(빈 문자열 포함) 호출 전에 막는다.
        String model = request.model() == null ? generator.defaultModel() : request.model().trim();
        if (!model.equals(generator.defaultModel()) && !allowedModels.contains(model)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (!generator.isConfigured()) {
            throw new BusinessException(ErrorCode.LLM_NOT_CONFIGURED);
        }
        if (!running.compareAndSet(false, true)) {
            throw new BusinessException(ErrorCode.GENERATION_IN_PROGRESS);
        }
        try {
            List<Target> plan = plan(count, categoryId);
            if (plan.isEmpty()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            Run run = new Run(model, count);
            lastRun = run;
            executor.execute(() -> execute(run, plan));
            return run.snapshot();
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
    }

    public QuestionGenerationDto.Status status() {
        Run run = lastRun;
        return run == null
                ? new QuestionGenerationDto.Status("IDLE", null, 0, 0, 0, null, null)
                : run.snapshot();
    }

    /** 서버 종료: 새 문제를 시작하지 않고, 만들던 문제 하나까지만 마무리한다. */
    @PreDestroy
    void shutdown() {
        stopRequested = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** 문제가 가장 적은 개념부터 고르고(개념 수보다 많으면 돌려 쓴다), 난이도는 균등하게 나눈다. */
    private List<Target> plan(int count, Integer categoryId) {
        List<ConceptSource> sources = concepts.findLeastCovered(categoryId);
        if (sources.isEmpty()) {
            return List.of();
        }
        int perLevel = count / 3;
        List<Difficulty> difficulties = new ArrayList<>();
        for (int i = 0; i < perLevel; i++) {
            difficulties.add(Difficulty.EASY);
            difficulties.add(Difficulty.HARD);
        }
        while (difficulties.size() < count) {
            difficulties.add(Difficulty.MEDIUM);
        }
        Collections.shuffle(difficulties);

        List<Target> plan = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            plan.add(new Target(sources.get(i % sources.size()), difficulties.get(i)));
        }
        return plan;
    }

    private void execute(Run run, List<Target> plan) {
        log.info("문제 생성 시작 model={} requested={}", run.model, plan.size());
        try {
            for (Target target : plan) {
                if (stopRequested) {
                    log.info("서버 종료 요청으로 문제 생성을 멈춥니다.");
                    break;
                }
                try {
                    if (generateOne(run.model, target)) {
                        run.succeeded.incrementAndGet();
                    } else {
                        run.failed.incrementAndGet();
                    }
                } catch (LlmException e) {
                    // 인증 오류처럼 다시 해도 소용없는 실패는 작업 전체를 멈춘다.
                    run.failed.incrementAndGet();
                    log.error("LLM 호출을 계속할 수 없어 문제 생성을 중단합니다: {}", e.getMessage());
                    break;
                }
            }
        } catch (RuntimeException e) {
            log.error("문제 생성 중 예상하지 못한 오류가 발생했습니다.", e);
        } finally {
            run.finish();
            running.set(false);
            log.info("문제 생성 종료 model={} succeeded={} failed={}", run.model, run.succeeded.get(), run.failed.get());
        }
    }

    /** 문제 하나를 최대 maxAttempts 번 시도한다. 저장하면 true, 모두 실패하면 false. */
    private boolean generateOne(String model, Target target) {
        ConceptSource concept = target.concept();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (stopRequested) {
                return false;
            }
            try {
                List<String> existing = questions.findContentsByConceptId(
                        concept.conceptId(), PageRequest.of(0, EXISTING_QUESTION_LIMIT));
                GeneratedQuestion generated = generator.generate(new QuestionRequest(model,
                        concept.categoryName(), concept.name(), concept.description(),
                        target.difficulty(), existing));
                Optional<String> problem = generated.validate(target.difficulty());
                if (problem.isPresent()) {
                    log.warn("생성한 문제를 쓸 수 없습니다 concept={} attempt={} reason={}",
                            concept.name(), attempt, problem.get());
                    continue;
                }
                Long questionId = saver.save(concept.conceptId(), target.difficulty(), generated);
                log.info("문제를 저장했습니다 questionId={} concept={} difficulty={}",
                        questionId, concept.name(), target.difficulty());
                return true;
            } catch (LlmException e) {
                if (e.isFatal()) {
                    throw e;
                }
                log.warn("문제 생성에 실패했습니다 concept={} attempt={} reason={}",
                        concept.name(), attempt, e.getMessage());
            } catch (RuntimeException e) {
                log.warn("문제 저장에 실패했습니다 concept={} attempt={}", concept.name(), attempt, e);
            }
        }
        return false;
    }

    private record Target(ConceptSource concept, Difficulty difficulty) { }

    private static final class Run {
        private final String model;
        private final int requested;
        private final OffsetDateTime startedAt = OffsetDateTime.now(SEOUL);
        private final AtomicInteger succeeded = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();
        private volatile OffsetDateTime finishedAt;

        private Run(String model, int requested) {
            this.model = model;
            this.requested = requested;
        }

        private void finish() {
            finishedAt = OffsetDateTime.now(SEOUL);
        }

        private QuestionGenerationDto.Status snapshot() {
            return new QuestionGenerationDto.Status(finishedAt == null ? "RUNNING" : "FINISHED", model,
                    requested, succeeded.get(), failed.get(), startedAt, finishedAt);
        }
    }
}
