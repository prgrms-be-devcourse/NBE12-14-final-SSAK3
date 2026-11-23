package com.react.report.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.react.report.dto.ReportFollowUp;
import com.react.report.dto.ReportFollowUp.Group;
import com.react.report.dto.ReportFollowUp.Kind;
import com.react.report.dto.ReportFollowUp.Question;
import com.react.report.dto.ReportFollowUp.Request;
import com.react.report.dto.ReportFollowUp.Status;
import com.react.report.dto.ReportTimeline;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.react.report.service.ReportDraftFields.*;
import static com.react.report.service.ReportGenerationException.invalidEvidence;
import static com.react.report.service.ReportGenerationException.invalidFormat;

/** 질문과 인용 구절은 반드시 현재 보고서의 기록으로 확인한다. 답변으로 지목된 발언은 마지막 요청보다 늦게 시작해야 한다. */
final class ReportFollowUpValidator {
    private final Map<Long, ReportTimeline.Chat> chats = new HashMap<>();
    private final Map<Long, ReportTimeline.Segment> speech = new HashMap<>();
    private final Set<Set<Quote>> seenQuestions = new HashSet<>();
    private final Set<Quote> initialQuotes = new HashSet<>();

    private ReportFollowUpValidator(ReportEvidenceCatalog catalog) {
        for (var source : catalog.sources()) {
            if (source.data() instanceof ReportTimeline.Chat chat) chats.put(chat.messageId(), chat);
            if (source.data() instanceof ReportTimeline.Segment segment) speech.put(segment.segmentId(), segment);
        }
    }

    static ReportFollowUp validate(JsonNode draft, ReportEvidenceCatalog catalog) {
        var validator = new ReportFollowUpValidator(catalog);
        if (validator.chats.isEmpty()) return ReportFollowUp.empty(Status.NO_QUESTIONS);
        try {
            return validator.validate(draft);
        } catch (ReportGenerationException e) {
            // 전체 요약은 별도로 검증했다. 근거를 확인하지 못한 이 영역만 비운다.
            return ReportFollowUp.empty(Status.UNAVAILABLE);
        }
    }

    private ReportFollowUp validate(JsonNode draft) {
        requireFields(draft, "reviewedChatIds", "groups");
        var reviewed = new HashSet<Long>();
        for (var id : array(draft, "reviewedChatIds")) {
            if (!reviewed.add(positiveId(id))) throw invalidEvidence();
        }
        if (!reviewed.equals(chats.keySet())) throw invalidEvidence();
        var groups = new ArrayList<Group>();
        for (var group : array(draft, "groups")) collectGroup(group, groups);
        if (groups.isEmpty()) {
            return ReportFollowUp.empty(seenQuestions.isEmpty() ? Status.NO_QUESTIONS : Status.ALL_ANSWERED);
        }
        groups.sort(Comparator.comparing((Group g) -> !hasReexplanation(g)).thenComparing(this::firstRequest, requestOrder()));
        return new ReportFollowUp(Status.CANDIDATES, List.copyOf(groups));
    }

    private void collectGroup(JsonNode group, List<Group> output) {
        requireFields(group, "title", "questions", "kind", "text", "segmentIds");
        var questions = array(group, "questions");
        if (questions.isEmpty()) throw invalidFormat();
        var pending = new ArrayList<Question>();
        boolean rebuild = false;
        for (var question : questions) {
            var review = review(question);
            if (review.answer() != Answer.CONFIRMED) pending.add(review.question());
            if (review.answer() != Answer.OPEN) rebuild = true;
        }
        if (pending.isEmpty()) return;
        if (rebuild) {
            // 그룹의 일부가 제외되었거나 답변 근거가 기각되면 그 그룹용 초안은 재사용할 수 없다.
            for (var question : pending) {
                output.add(new Group(question.requests().getFirst().quote(), List.of(question), Kind.PREPARATION,
                        "질문 원문을 확인하고 답변에 필요한 설명이나 예시를 준비해 주세요.", List.of()));
            }
            return;
        }
        var kind = switch (string(group.get("kind"))) {
            case "DRAFT" -> Kind.DRAFT;
            case "PREPARATION" -> Kind.PREPARATION;
            default -> throw invalidFormat();
        };
        var ids = new ArrayList<Long>();
        var seen = new HashSet<Long>();
        for (var entry : array(group, "segmentIds")) {
            long id = positiveId(entry);
            if (!speech.containsKey(id) || !seen.add(id)) throw invalidEvidence();
            ids.add(id);
        }
        if (kind == Kind.DRAFT && ids.isEmpty()) throw invalidEvidence();
        if (kind == Kind.PREPARATION && !ids.isEmpty()) throw invalidEvidence();
        output.add(new Group(string(group.get("title")), List.copyOf(pending), kind, string(group.get("text")),
                List.copyOf(ids)));
    }

    private Review review(JsonNode node) {
        requireFields(node, "requests", "answerSegmentIds");
        var requests = new ArrayList<Request>();
        var seen = new HashSet<Quote>();
        for (var entry : array(node, "requests")) {
            requireFields(entry, "messageId", "quote", "reexplanation");
            long id = positiveId(entry.get("messageId"));
            String quote = string(entry.get("quote"));
            if (!entry.get("reexplanation").isBoolean()) throw invalidFormat();
            boolean repeat = entry.get("reexplanation").booleanValue();
            var chat = chats.get(id);
            var source = new Quote(id, quote);
            if (chat == null || !chat.text().contains(quote) || !seen.add(source)) throw invalidEvidence();
            // 서로 다른 질문이 같은 후속 요청(예: "둘 다 다시요")을 함께 가리킬 수 있다.
            if (!repeat && !initialQuotes.add(source)) throw invalidEvidence();
            requests.add(new Request(id, quote, repeat));
        }
        if (requests.isEmpty() || !seenQuestions.add(Set.copyOf(seen))) throw invalidEvidence();
        requests.sort(requestOrder());
        var question = new Question(List.copyOf(requests));
        var answerIds = array(node, "answerSegmentIds");
        if (answerIds.isEmpty()) return new Review(question, Answer.OPEN);
        Instant lastRequest = timeOf(requests.getLast());
        for (var ref : answerIds) {
            var segment = ref.isIntegralNumber() && ref.canConvertToLong() ? speech.get(ref.longValue()) : null;
            if (segment == null || !segment.startAt().isAfter(lastRequest)) return new Review(question, Answer.UNVERIFIED);
        }
        return new Review(question, Answer.CONFIRMED);
    }

    private Instant timeOf(Request request) {
        return chats.get(request.messageId()).createdAt();
    }

    private Comparator<Request> requestOrder() {
        return Comparator.comparing(this::timeOf).thenComparingLong(Request::messageId).thenComparing(Request::quote);
    }

    private Request firstRequest(Group group) {
        return group.questions().stream().map(question -> question.requests().getFirst()).min(requestOrder()).orElseThrow();
    }

    private boolean hasReexplanation(Group group) {
        return group.questions().stream().flatMap(q -> q.requests().stream()).anyMatch(Request::reexplanation);
    }

    private enum Answer { OPEN, CONFIRMED, UNVERIFIED }
    private record Review(Question question, Answer answer) {}
    private record Quote(long messageId, String quote) {}
}
