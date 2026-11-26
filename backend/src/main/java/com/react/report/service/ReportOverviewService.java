package com.react.report.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.react.report.dto.ReportContent;
import com.react.report.dto.ReportFollowUp;
import com.react.report.dto.ReportInput;
import com.react.report.dto.ReportTimeline;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReportOverviewService {
    private final ReportInputService inputs;
    private final ReportOverviewClient client;
    private final ReportOverviewValidator validator;
    private final ObjectMapper mapper;

    record Snapshot(ReportInput input, ReportTimeline timeline, ReportEvidenceCatalog catalog) {}

    /** 입력 수집 트랜잭션이 끝난 뒤 동일한 스냅샷으로 생성·검증한다. 호출 실패는 작업자에게 그대로 반환한다. */
    public ReportContent generate(long roomId, String hostEmail) {
        var snapshot = prepare(roomId, hostEmail);
        JsonNode draft = snapshot.catalog().hasAudience() ? client.generate(prompt(snapshot)) : null;
        return assemble(snapshot, draft);
    }

    Snapshot prepare(long roomId, String hostEmail) {
        return snapshot(inputs.collect(roomId, hostEmail));
    }

    Snapshot snapshot(ReportInput input) {
        ReportTimeline timeline = ReportTimelineAssembler.assemble(input);
        return new Snapshot(input, timeline, ReportEvidenceCatalog.from(timeline));
    }

    JsonNode prompt(Snapshot snapshot) {
        return modelInput(snapshot.input(), snapshot.timeline(), snapshot.catalog());
    }

    ReportContent assemble(Snapshot snapshot, JsonNode draft) {
        var timeline = snapshot.timeline();
        var catalog = snapshot.catalog();
        JsonNode overview;
        ReportFollowUp followUp;
        if (catalog.hasAudience()) {
            overview = validator.validate(draft, timeline, catalog);
            followUp = ReportFollowUpValidator.validate(draft.get("followUp"), catalog);
        } else {
            var empty = mapper.createObjectNode();
            empty.putArray("mainInterests");
            empty.putArray("reviewPoints");
            empty.putArray("suggestions");
            overview = empty;
            followUp = ReportFollowUp.empty(ReportFollowUp.Status.NO_QUESTIONS);
        }
        return new ReportContent(overview, followUp, timeline.timeline(), timeline.dataLimitations(),
                "동일 시간대의 관찰은 인과관계를 의미하지 않습니다. AI 요약은 원문 및 서버 집계와 구분합니다.");
    }

    private ObjectNode modelInput(ReportInput input, ReportTimeline timeline, ReportEvidenceCatalog catalog) {
        var payload = mapper.createObjectNode().put("roomId", input.roomId());
        payload.set("windows", mapper.valueToTree(catalog.windows()));
        var evidence = payload.putArray("evidenceRecords");
        var aliases = input.chats().stream()
                .collect(Collectors.toMap(ReportInput.Chat::messageId, ReportInput.Chat::participantId));
        for (var source : catalog.sources()) {
            var ref = source.ref();
            var record = evidence.addObject().put("type", ref.type()).put("id", ref.id());
            record.set("relatedWindows", mapper.valueToTree(source.relatedWindows()));
            ObjectNode data = mapper.valueToTree(source.data());
            if (source.data() instanceof ReportTimeline.Chat chat) {
                data.put("participantId", aliases.get(chat.messageId()));
            }
            record.set("data", data);
        }
        var opinions = timeline.timeline().stream().flatMap(window -> window.audienceOpinions().stream()).toList();
        payload.set("audienceOpinions", mapper.valueToTree(opinions));
        payload.set("dataLimitations", mapper.valueToTree(timeline.dataLimitations()));
        return payload;
    }
}
