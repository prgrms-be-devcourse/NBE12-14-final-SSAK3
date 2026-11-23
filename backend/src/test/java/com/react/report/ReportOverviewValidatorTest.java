package com.react.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.react.ai.domain.AiAnalysisStatus;
import com.react.poll.dto.PollOptionResultView;
import com.react.poll.entity.PollStatus;
import com.react.poll.entity.PollType;
import com.react.poll.entity.UnderstandingStatus;
import com.react.report.dto.ReportInput;
import com.react.report.dto.ReportTimeline;
import com.react.report.service.ReportGenerationException;
import com.react.report.service.ReportOverviewValidator;
import com.react.report.service.ReportTimelineAssembler;
import com.react.stt.dto.SttGap;
import com.react.stt.dto.SttRecordsView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.react.report.service.ReportGenerationException.Reason.INVALID_REPORT_EVIDENCE;
import static com.react.report.service.ReportGenerationException.Reason.INVALID_REPORT_FORMAT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportOverviewValidatorTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Instant START = Instant.parse("2026-11-21T00:00:00Z");
    private static final ReportInput.Window FIRST = new ReportInput.Window(START, START.plusSeconds(60));
    private static final ReportInput.Window SECOND = new ReportInput.Window(START.plusSeconds(60), START.plusSeconds(120));
    private static final ReportInput.Window THIRD = new ReportInput.Window(START.plusSeconds(120), START.plusSeconds(180));
    private final ReportOverviewValidator validator = new ReportOverviewValidator();
    private final ReportTimeline timeline = timeline();

    @Test
    void 같은_투표를_상위에_한번_두고_각_입장의_응답한_선택지들을_보존한다() {
        var fast = leaf("속도를 높여 달라", List.of(poll(5, 51, 52)), FIRST);
        var slow = leaf("속도를 낮춰 달라", List.of(poll(5, 53)), FIRST);
        var overview = validator.validate(draft("mainInterests", opposing(fast, slow)), timeline);
        var card = overview.at("/mainInterests/0");
        assertThat(card.get("evidence")).isEqualTo(array(poll(5)));
        assertThat(card.at("/relatedWindows")).isEqualTo(windows(FIRST));
        assertThat(card.at("/perspectives/0/evidence")).isEqualTo(array(poll(5, 51, 52)));
        assertThat(card.at("/perspectives/1/evidence")).isEqualTo(array(poll(5, 53)));
        assertThat(card.get("limitations")).isEmpty();
    }

    @Test
    void 마감_후_재설명_요청은_원래_시간대를_유지하며_다른_채팅은_원문에_남긴다() {
        var item = regular("가스비 계산 체크의 응답이 갈렸고, 예시를 요청한 채팅이 있었습니다.",
                List.of(poll(6), citation("CHAT_MESSAGE", 1)), SECOND, FIRST);
        var overview = validator.validate(draft("reviewPoints", item), timeline);
        var card = overview.at("/reviewPoints/0");
        assertThat(card.get("relatedWindows")).isEqualTo(windows(FIRST, SECOND));
        assertThat(card.get("perspectives")).isEmpty();
        assertThat(card.get("evidence")).isEqualTo(array(poll(6), citation("CHAT_MESSAGE", 1)));
        assertThat(card.get("limitations")).hasSize(1);
        assertThat(card.get("limitations").get(0).asText()).contains("청중 의견 AI 분석 실패");
        assertThat(timeline.timeline().get(1).chatMessages()).extracting(ReportTimeline.Chat::messageId)
                .containsExactly(1L, 2L);
        assertThat(timeline.timeline().get(1).chatMessages().getFirst().createdAt()).isEqualTo(START.plusSeconds(72));
    }

    @Test
    void 같은_숫자_ID의_다른_종류와_여러_분에_걸친_발언_시간을_모두_검증한다() {
        var refs = List.of(citation("CHAT_MESSAGE", 1), citation("STT_SEGMENT", 1));
        var overview = validator.validate(draft("suggestions", regular("검토 제안", refs, FIRST, SECOND)), timeline);
        assertThat(overview.at("/suggestions/0/evidence")).hasSize(2);
        assertThat(overview.at("/suggestions/0/limitations")).hasSize(1);
        reject(draft("suggestions", regular("출처 시간대 일부 누락", refs, SECOND)), INVALID_REPORT_EVIDENCE);
    }

    @Test
    void 종료_미응답_투표와_없는_원문_및_강사_단독_근거를_거부한다() {
        for (var reference : List.of(poll(7), citation("CHAT_MESSAGE", 200), citation("STT_SEGMENT", 1))) {
            reject(draft("suggestions", regular("제안", List.of(reference), FIRST)), INVALID_REPORT_EVIDENCE);
        }
        reject(draft("reviewPoints", regular("시간대 오류", List.of(citation("CHAT_MESSAGE", 1)), FIRST)),
                INVALID_REPORT_EVIDENCE);
    }

    @Test
    void 응답자가_있어도_진행_중인_투표를_인용하면_실패한다() {
        var row = timeline.timeline().getFirst();
        var poll = row.polls().getFirst();
        var openPoll = new ReportTimeline.Poll(poll.pollId(), poll.type(), PollStatus.OPEN, poll.question(),
                poll.openedAt(), null, poll.totalParticipants(), poll.respondedParticipants(), poll.unrespondedParticipants(),
                poll.responseRate(), poll.counts(), poll.options(), null, null, null);
        var openWindow = new ReportTimeline.Window(row.startAt(), row.endAt(), row.sttSegments(), row.sttGaps(),
                row.chatMessages(), row.understandingChecks(), row.audienceOpinions(), List.of(openPoll));
        var incomplete = new ReportTimeline(List.of(openWindow), List.of());
        var item = regular("마감 전 집계를 인용한 항목", List.of(poll(5)), FIRST);
        assertThatThrownBy(() -> validator.validate(draft("mainInterests", item), incomplete))
                .isInstanceOfSatisfying(ReportGenerationException.class,
                        ex -> assertThat(ex.reason()).isEqualTo(INVALID_REPORT_EVIDENCE));
    }

    @Test
    void 잘못된_선택지나_겹치는_선택지로_두_입장을_만들_수_없다() {
        var slow = leaf("천천히", List.of(poll(5, 53)), FIRST);
        // 각각 선택지 생략, 응답 0명, 다른 투표의 선택지, 양쪽이 같은 선택지를 인용한 경우.
        for (var bad : List.of(poll(5), poll(5, 54), poll(5, 61), poll(5, 53))) {
            var fast = leaf("빠르게", List.of(bad), FIRST);
            reject(draft("mainInterests", opposing(fast, slow)), INVALID_REPORT_EVIDENCE);
        }
    }

    @Test
    void 이해도_분포만으로_상반_의견과_재설명_요청을_생성하지_않는다() {
        var check = List.of(poll(6));
        var valid = regular("이해도 응답이 갈렸습니다.", check, FIRST);
        assertThat(validator.validate(draft("reviewPoints", valid), timeline).at("/reviewPoints/0/perspectives"))
                .isEmpty();
        reject(draft("suggestions", valid), INVALID_REPORT_EVIDENCE);
        reject(draft("reviewPoints", valid, valid), INVALID_REPORT_EVIDENCE);
        reject(draft("reviewPoints", opposing(leaf("찬성", check, FIRST), leaf("반대", check, FIRST))),
                INVALID_REPORT_EVIDENCE);
    }

    @Test
    void 데이터_한계는_관련_입장과_상위_항목에만_연결한다() {
        var side1 = leaf("예시를 보고 싶다", List.of(citation("CHAT_MESSAGE", 1)), SECOND);
        var side2 = leaf("이론에 집중하고 싶다", List.of(citation("CHAT_MESSAGE", 3)), THIRD);
        var overview = validator.validate(draft("mainInterests", opposing(side1, side2)), timeline);
        var card = overview.at("/mainInterests/0");
        assertThat(card.at("/perspectives/0/limitations")).hasSize(1);
        assertThat(card.at("/perspectives/0/limitations/0").textValue()).contains("청중 의견 AI 분석 실패");
        assertThat(card.at("/perspectives/1/limitations")).hasSize(1);
        assertThat(card.at("/perspectives/1/limitations/0").textValue()).contains("발언 기록이 없어");
        assertThat(card.get("limitations")).hasSize(2);
    }

    @Test
    void 형식_오류나_서버_전용_필드를_한_항목에라도_포함하면_실패한다() {
        var valid = regular("예시 요청", List.of(citation("CHAT_MESSAGE", 1)), SECOND);
        reject(draft("mainInterests", valid, valid, valid, valid), INVALID_REPORT_FORMAT);
        reject(draft("mainInterests", valid.deepCopy().put("text", "  ")), INVALID_REPORT_FORMAT);
        reject(draft("mainInterests", valid.deepCopy().put("limitations", "허위 한계")), INVALID_REPORT_FORMAT);
        reject(draft("mainInterests", opposing(leaf("한쪽만", List.of(poll(5, 51)), FIRST))), INVALID_REPORT_FORMAT);
        var plain = draft("mainInterests", valid);
        ((ObjectNode) plain.at("/overview/mainInterests/0/evidence/0")).put("id", "1");
        reject(plain, INVALID_REPORT_FORMAT);
    }

    private void reject(JsonNode input, ReportGenerationException.Reason reason) {
        assertThatThrownBy(() -> validator.validate(input, timeline))
                .isInstanceOfSatisfying(ReportGenerationException.class, ex -> assertThat(ex.reason()).isEqualTo(reason));
    }

    private static ObjectNode draft(String field, ObjectNode... items) {
        var draft = JSON.objectNode();
        var overview = draft.putObject("overview");
        for (String name : List.of("mainInterests", "reviewPoints", "suggestions")) overview.putArray(name);
        overview.set(field, array(items));
        return draft;
    }

    private static ObjectNode regular(String text, List<ObjectNode> refs, ReportInput.Window... times) {
        ObjectNode item = leaf(text, refs, times);
        item.putArray("perspectives");
        return item;
    }

    private static ObjectNode opposing(ObjectNode... sides) {
        var item = leaf("서로 다른 반응", List.of());
        item.set("perspectives", array(sides));
        return item;
    }

    private static ObjectNode leaf(String text, List<ObjectNode> refs, ReportInput.Window... times) {
        var node = JSON.objectNode().put("text", text);
        refs.forEach(node.putArray("evidence")::add);
        node.set("relatedWindows", windows(times));
        return node;
    }

    private static ArrayNode windows(ReportInput.Window... ranges) {
        var nodes = JSON.arrayNode();
        for (var w : ranges) nodes.addObject().put("startAt", w.startAt().toString()).put("endAt", w.endAt().toString());
        return nodes;
    }

    private static ObjectNode poll(long id, long... options) {
        var node = citation("POLL_RESULT", id);
        if (options.length > 0) {
            var ids = node.putArray("optionIds");
            for (long option : options) ids.add(option);
        }
        return node;
    }

    private static ObjectNode citation(String type, long id) {
        return JSON.objectNode().put("type", type).put("id", id);
    }

    private static ArrayNode array(ObjectNode... values) {
        return JSON.arrayNode().addAll(List.of(values));
    }

    private static ReportTimeline timeline() {
        var general = new ReportInput.Poll(5, PollType.GENERAL, "강연 속도", START, START.plusSeconds(30),
                FIRST, 12, 9, 3, 75, List.of(
                        new PollOptionResultView(51, "조금 더 빠르게", null, 4, 44.4, 33.3),
                        new PollOptionResultView(52, "훨씬 더 빠르게", null, 2, 22.2, 16.7),
                        new PollOptionResultView(53, "더 천천히", null, 3, 33.3, 25),
                        new PollOptionResultView(54, "지금대로", null, 0, 0, 0)), null);
        var check = new ReportInput.Poll(6, PollType.UNDERSTANDING, "가스비 계산", START.plusSeconds(31),
                START.plusSeconds(59), FIRST, 12, 10, 2, 83.3, List.of(
                        new PollOptionResultView(61, "이해됨", UnderstandingStatus.UNDERSTOOD, 6, 60, 50),
                        new PollOptionResultView(62, "헷갈림", UnderstandingStatus.CONFUSED, 4, 40, 33.3)),
                new ReportInput.Understanding(4, 40, true));
        var empty = new ReportInput.Poll(7, PollType.GENERAL, "아무도 응답하지 않은 투표", START.plusSeconds(60),
                START.plusSeconds(70), SECOND, 12, 0, 12, 0, List.of(), null);
        var records = new SttRecordsView(1, List.of(new SttRecordsView.Segment(1, 1, "가스비 예시",
                START.plusSeconds(58), START.plusSeconds(62), START.plusSeconds(63))), List.of(
                new SttRecordsView.Gap(20, 1, SttGap.Reason.ERROR, START.plusSeconds(140), START.plusSeconds(148))));
        var chats = List.of(new ReportInput.Chat(1, "a", "가스비 계산 예시를 다시 보여주세요", START.plusSeconds(72), SECOND),
                new ReportInput.Chat(2, "b", "한 번 더요", START.plusSeconds(74), SECOND),
                new ReportInput.Chat(3, "c", "이론에 집중해 주세요", START.plusSeconds(160), THIRD));
        var failed = new ReportInput.Analysis(4, SECOND.startAt(), START.plusSeconds(90), AiAnalysisStatus.FAILED,
                List.of());
        return ReportTimelineAssembler.assemble(new ReportInput(1, START, THIRD.endAt(), records, chats, List.of(failed),
                List.of(general, check, empty)));
    }
}
