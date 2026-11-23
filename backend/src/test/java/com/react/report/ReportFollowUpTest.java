package com.react.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.react.report.dto.ReportInput;
import com.react.report.service.ReportInputService;
import com.react.report.service.ReportOverviewService;
import com.react.report.service.ReportOverviewValidator;
import com.react.stt.dto.SttRecordsView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportFollowUpTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Instant start = Instant.parse("2026-11-23T00:00:00Z");
    private final Instant end = start.plusSeconds(60);
    private final AtomicInteger calls = new AtomicInteger();

    @Test
    void 같은_채팅의_두_질문을_따로_검토하고_답한_질문만_제외한다() throws Exception {
        var draft = mapper.readTree("""
                {"reviewedChatIds":[10,11,12],"groups":[
                  {"title":"비용 계산","kind":"PREPARATION","text":"계산 예시를 준비하세요.","segmentIds":[],
                   "questions":[{"requests":[{"messageId":10,"quote":"비용은 어떻게 계산해요?","reexplanation":false}],"answerSegmentIds":[20]}]},
                  {"title":"실패 처리","kind":"PREPARATION","text":"실패한 거래의 수수료 처리 예시를 준비하세요.","segmentIds":[],
                   "questions":[{"requests":[{"messageId":10,"quote":"실패해도 내나요?","reexplanation":false}],"answerSegmentIds":[]}]}
                ]}
                """);
        var content = generate(draft);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(content.at("/followUp/status").textValue()).isEqualTo("CANDIDATES");
        assertThat(content.at("/followUp/groups")).hasSize(1);
        assertThat(content.at("/followUp/groups/0/questions/0/requests/0/quote").textValue()).isEqualTo("실패해도 내나요?");
        assertThat(content.at("/timeline/0/chatMessages/0/text").textValue()).isEqualTo("비용은 어떻게 계산해요? 실패해도 내나요?");
    }

    @Test
    void 재요청_전에_시작한_발언이나_같은_시각의_발언은_답변으로_확정할_수_없다() throws Exception {
        var repeat = repeatedQuestion();
        for (var ids : List.of(List.of(20), List.of(21), List.of(22, 999), List.of(-2))) {
            repeat.set("answerSegmentIds", mapper.valueToTree(ids));
            var result = generate(draft(group("답변했다고 판단한 질문", repeat)));
            var candidate = result.at("/followUp/groups/0");
            assertThat(result.at("/followUp/status").textValue()).isEqualTo("CANDIDATES");
            assertThat(candidate.get("kind").textValue()).isEqualTo("PREPARATION");
            assertThat(candidate.at("/questions/0/requests")).hasSize(2);
            assertThat(candidate.at("/questions/0/requests/1/messageId").longValue()).isEqualTo(11);
            assertThat(candidate.get("segmentIds")).isEmpty();
        }
        repeat.set("answerSegmentIds", mapper.valueToTree(List.of(22)));
        var confirmed = generate(draft(group("실패 수수료", repeat)));
        assertThat(confirmed.at("/followUp/status").textValue()).isEqualTo("ALL_ANSWERED");
        assertThat(confirmed.at("/followUp/groups")).isEmpty();
    }

    @Test
    void 섞인_묶음에서_해결된_질문의_초안이나_발언을_다른_질문에_붙이지_않는다() throws Exception {
        var cost = question(10, "비용은 어떻게 계산해요?");
        cost.set("answerSegmentIds", mapper.valueToTree(List.of(20)));
        var mixed = group("두 질문의 답변", cost, question(10, "실패해도 내나요?"));
        mixed.put("kind", "DRAFT").put("text", "비용은 이렇게 계산하면 됩니다.");
        mixed.set("segmentIds", mapper.valueToTree(List.of(20)));
        var candidate = generate(draft(mixed)).at("/followUp/groups/0");
        assertThat(candidate.get("title").textValue()).isEqualTo("실패해도 내나요?");
        assertThat(candidate.get("kind").textValue()).isEqualTo("PREPARATION");
        assertThat(candidate.get("segmentIds")).isEmpty();
    }

    @Test
    void 대상_채팅_검토나_후보_근거가_잘못되면_요약과_시간대별_기록을_유지한다() throws Exception {
        var validGroup = group("실패 수수료", question(10, "실패해도 내나요?"));
        var badCases = new ArrayList<JsonNode>();
        for (var ids : List.of(List.of(10, 11), List.of(10, 11, 12, 12), List.of(10, 11, 999))) {
            var partial = draft(validGroup);
            partial.set("reviewedChatIds", mapper.valueToTree(ids));
            badCases.add(partial);
        }
        badCases.add(draft(group("다른 방의 질문", question(999, "실패해도 내나요?"))));
        badCases.add(draft(group("원문에 없는 구절", question(10, "모두 수수료가 궁금해요."))));
        badCases.add(draft(validGroup, validGroup));
        var relabeled = question(10, "실패해도 내나요?");
        ((ObjectNode) relabeled.at("/requests/0")).put("reexplanation", true);
        badCases.add(draft(validGroup, group("같은 구절을 재설명으로 중복 분류", relabeled)));
        badCases.add(draft(validGroup.deepCopy().put("kind", "DRAFT")));
        for (String kind : List.of("DRAFT", "PREPARATION")) {
            var badRef = validGroup.deepCopy().put("kind", kind);
            badRef.set("segmentIds", mapper.valueToTree(List.of(kind.equals("DRAFT") ? 999 : 20)));
            badCases.add(draft(badRef));
        }
        badCases.add(null);
        for (var malformed : badCases) {
            var content = generate(malformed);
            assertThat(content.at("/followUp/status").textValue()).isEqualTo("UNAVAILABLE");
            assertThat(content.at("/followUp/groups")).isEmpty();
            assertThat(content.at("/overview").has("mainInterests")).isTrue();
            assertThat(content.at("/timeline/0/chatMessages")).hasSize(3);
        }
    }

    @Test
    void 모든_미확인_묶음을_재설명_요청과_최초_질문_시각_순으로_보관한다() throws Exception {
        var chats = List.of(chat(40, 35, "C?"), chat(30, 8, "D?"), chat(10, 12, "A?"),
                chat(20, 3, "B?"), chat(50, 43, "A를 다시요."), chat(60, 50, "B를 다시요."));
        var stt = new SttRecordsView(1, List.of(segment(20, 1, 2)), List.of());
        var input = new ReportInput(1, start, end, stt, chats, List.of(), List.of());
        var a = question(10, "A?");
        var b = question(20, "B?");
        a.withArray("requests").addObject().put("quote", "A를 다시요.").put("messageId", 50).put("reexplanation", true);
        b.withArray("requests").addObject().put("quote", "B를 다시요.").put("messageId", 60).put("reexplanation", true);
        var draftGroup = group("A", a).put("kind", "DRAFT");
        draftGroup.withArray("segmentIds").add(20);
        var all = draft(group("C", question(40, "C?")), draftGroup, group("D", question(30, "D?")), group("B", b));
        all.set("reviewedChatIds", mapper.valueToTree(List.of(10, 20, 30, 40, 50, 60)));
        var groups = generate(input, all).at("/followUp/groups");
        assertThat(groups).extracting(node -> node.path("title").textValue()).containsExactly("B", "A", "D", "C");
        assertThat(groups.at("/1/kind").textValue()).isEqualTo("DRAFT");
        assertThat(groups.at("/1/segmentIds/0").longValue()).isEqualTo(20);
    }

    @Test
    void 전체_채팅을_검토해_질문이_없는_경우와_청중_기록이_전혀_없는_경우() {
        assertThat(generate(draft()).at("/followUp/status").textValue()).isEqualTo("NO_QUESTIONS");
        var stt = new SttRecordsView(1, List.of(segment(20, 1, 2)), List.of());
        var input = new ReportInput(1, start, end, stt, List.of(), List.of(), List.of());
        var sttOnly = generate(input, null);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(sttOnly.at("/followUp/status").textValue()).isEqualTo("NO_QUESTIONS");
        assertThat(sttOnly.at("/timeline/0/sttSegments")).hasSize(1);
    }

    private ObjectNode repeatedQuestion() {
        var node = question(10, "실패해도 내나요?");
        node.withArray("requests").addObject().put("messageId", 11).put("quote", "한 번 더 설명해 주세요.").put("reexplanation", true);
        return node;
    }

    private ObjectNode question(long chatId, String quote) {
        var node = mapper.createObjectNode();
        node.putArray("requests").addObject().put("messageId", chatId).put("quote", quote).put("reexplanation", false);
        node.putArray("answerSegmentIds");
        return node;
    }

    private ObjectNode group(String title, ObjectNode... questions) {
        var node = mapper.createObjectNode().put("title", title).put("kind", "PREPARATION").put("text", "관련 예시를 준비하세요.");
        node.set("questions", mapper.valueToTree(questions));
        node.putArray("segmentIds");
        return node;
    }

    private ObjectNode draft(ObjectNode... groups) {
        var node = mapper.createObjectNode();
        node.set("reviewedChatIds", mapper.valueToTree(List.of(10, 11, 12)));
        node.set("groups", mapper.valueToTree(groups));
        return node;
    }

    private JsonNode generate(JsonNode followUp) {
        var chats = List.of(chat(10, 5, "비용은 어떻게 계산해요? 실패해도 내나요?"),
                chat(11, 15, "실패했을 때도 한 번 더 설명해 주세요."), chat(12, 24, "감사합니다."));
        var speech = new SttRecordsView(1, List.of(segment(20, 8, 18), segment(21, 15, 17), segment(22, 16, 20)), List.of());
        return generate(new ReportInput(1, start, end, speech, chats, List.of(), List.of()), followUp);
    }

    private JsonNode generate(ReportInput input, JsonNode followUp) {
        var inputs = mock(ReportInputService.class);
        when(inputs.collect(1, "host")).thenReturn(input);
        ObjectNode response = mapper.createObjectNode();
        var overview = response.putObject("overview");
        for (var section : List.of("mainInterests", "reviewPoints", "suggestions")) overview.putArray(section);
        if (followUp != null) response.set("followUp", followUp);
        var service = new ReportOverviewService(inputs, ignored -> {
            calls.incrementAndGet();
            return response;
        }, new ReportOverviewValidator(), mapper);
        return mapper.valueToTree(service.generate(1, "host"));
    }

    private ReportInput.Chat chat(long id, int seconds, String text) {
        return new ReportInput.Chat(id, "audience", text, start.plusSeconds(seconds), new ReportInput.Window(start, end));
    }

    private SttRecordsView.Segment segment(long id, int from, int to) {
        return new SttRecordsView.Segment(id, 1, "발언 " + id, start.plusSeconds(from), start.plusSeconds(to), start.plusSeconds(to));
    }
}
