package com.react.report.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.react.poll.entity.PollType;
import com.react.report.dto.ReportInput.Window;
import com.react.report.dto.ReportTimeline;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.react.report.service.ReportDraftFields.*;
import static com.react.report.service.ReportGenerationException.invalidEvidence;
import static com.react.report.service.ReportGenerationException.invalidFormat;

/** LLM 초안에서 검증된 참조만 복사한다. 시간대와 한계는 해당 강연의 입력 스냅샷으로 확정한다. */
@Component
public class ReportOverviewValidator {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final List<String> SECTIONS = List.of("mainInterests", "reviewPoints", "suggestions");
    private static final Comparator<Window> TIME_ORDER = Comparator.comparing(Window::startAt)
            .thenComparing(Window::endAt);

    public JsonNode validate(JsonNode draft, ReportTimeline timeline) {
        return validate(draft, timeline, ReportEvidenceCatalog.from(timeline));
    }

    JsonNode validate(JsonNode draft, ReportTimeline timeline, ReportEvidenceCatalog catalog) {
        if (draft != null && draft.has("followUp")) requireFields(draft, "overview", "followUp");
        else requireFields(draft, "overview");
        JsonNode overview = draft.get("overview");
        requireFields(overview, "mainInterests", "reviewPoints", "suggestions");
        var limitations = new ReportOverviewLimitations(timeline);
        var result = JSON.objectNode();
        var summarizedChecks = new HashSet<Long>();
        for (String section : SECTIONS) {
            ArrayNode items = array(overview, section);
            if (items.size() > 3) throw invalidFormat();
            var output = result.putArray(section);
            for (var item : items) {
                output.add(validateItem(item, section, catalog, limitations, summarizedChecks));
            }
        }
        return result;
    }

    private ObjectNode validateItem(JsonNode item, String section, ReportEvidenceCatalog catalog,
                                    ReportOverviewLimitations limitations, Set<Long> summarizedChecks) {
        requireFields(item, "text", "evidence", "relatedWindows", "perspectives");
        ObjectNode result = summary(item);
        ArrayNode perspectives = array(item, "perspectives");
        ArrayNode sides = result.putArray("perspectives");
        if (perspectives.isEmpty()) {
            Leaf leaf = validateLeaf(item, false, catalog, limitations);
            if (leaf.onlyUnderstanding()) {
                for (var poll : leaf.polls) {
                    if (poll.options().stream().filter(option -> option.count() > 0).count() > 1
                            && (!section.equals("reviewPoints") || !summarizedChecks.add(poll.pollId()))) {
                        throw invalidEvidence();
                    }
                }
            }
            leaf.writeTo(result);
            return result;
        }

        if (perspectives.size() < 2 || !array(item, "evidence").isEmpty()
                || !array(item, "relatedWindows").isEmpty()) {
            throw invalidFormat();
        }
        var all = new Leaf();
        var usedChoices = new HashSet<PollChoice>();
        for (var side : perspectives) {
            requireFields(side, "text", "evidence", "relatedWindows");
            ObjectNode sideResult = summary(side);
            Leaf leaf = validateLeaf(side, true, catalog, limitations);
            if (leaf.onlyUnderstanding()) throw invalidEvidence();
            for (var citation : leaf.citations.entrySet()) {
                for (long id : citation.getValue()) {
                    if (!usedChoices.add(new PollChoice(citation.getKey().id(), id))) throw invalidEvidence();
                }
                // 상위 근거는 투표당 한 건만 표시하고 선택지 강조는 입장 안에 보존한다.
                all.citations.put(citation.getKey(), Set.of());
            }
            leaf.writeTo(sideResult);
            sides.add(sideResult);
            all.windows.addAll(leaf.windows);
            all.limitations.addAll(leaf.limitations);
        }
        all.writeTo(result);
        return result;
    }

    private Leaf validateLeaf(JsonNode node, boolean perspective, ReportEvidenceCatalog catalog,
                              ReportOverviewLimitations limitations) {
        ArrayNode evidence = array(node, "evidence");
        ArrayNode relatedWindows = array(node, "relatedWindows");
        if (evidence.isEmpty() || relatedWindows.isEmpty()) throw invalidFormat();
        var leaf = new Leaf();
        for (JsonNode citation : evidence) {
            boolean hasOptions = citation.has("optionIds");
            if (hasOptions) requireFields(citation, "type", "id", "optionIds");
            else requireFields(citation, "type", "id");
            String type = string(citation.get("type"));
            if (!Set.of("POLL_RESULT", "CHAT_MESSAGE", "STT_SEGMENT").contains(type)) throw invalidFormat();
            var source = catalog.find(type, positiveId(citation.get("id")));
            if (source == null) throw invalidEvidence();
            var selected = leaf.citations.computeIfAbsent(source.ref(), ignored -> new LinkedHashSet<>());
            if (source.data() instanceof ReportTimeline.Poll poll) {
                if (hasOptions) {
                    if (poll.type() != PollType.GENERAL) throw invalidEvidence();
                    ArrayNode options = array(citation, "optionIds");
                    if (options.isEmpty()) throw invalidFormat();
                    for (var option : options) {
                        long id = positiveId(option);
                        if (poll.options().stream().noneMatch(row -> row.optionId() == id && row.count() > 0)) {
                            throw invalidEvidence();
                        }
                        selected.add(id);
                    }
                } else if (perspective && poll.type() == PollType.GENERAL) {
                    throw invalidEvidence();
                }
                leaf.polls.add(poll);
            } else if (hasOptions) {
                throw invalidFormat();
            }
            leaf.windows.addAll(source.relatedWindows());
        }
        if (leaf.citations.keySet().stream().allMatch(key -> key.type().equals("STT_SEGMENT"))) {
            throw invalidEvidence();
        }
        var declared = new HashSet<Window>();
        for (var window : relatedWindows) {
            requireFields(window, "startAt", "endAt");
            try {
                declared.add(new Window(Instant.parse(string(window.get("startAt"))),
                        Instant.parse(string(window.get("endAt")))));
            } catch (DateTimeException e) {
                throw invalidFormat();
            }
        }
        if (!declared.equals(leaf.windows)) throw invalidEvidence();
        leaf.limitations.addAll(limitations.forEvidence(leaf.windows, leaf.polls));
        return leaf;
    }

    private static ObjectNode summary(JsonNode value) {
        return JSON.objectNode().put("text", string(value.get("text")));
    }

    private record PollChoice(long pollId, long optionId) {}

    private static final class Leaf {
        final Map<ReportEvidenceCatalog.Ref, Set<Long>> citations = new LinkedHashMap<>();
        final Set<Window> windows = new TreeSet<>(TIME_ORDER);
        final Set<String> limitations = new LinkedHashSet<>();
        final Set<ReportTimeline.Poll> polls = new LinkedHashSet<>();

        boolean onlyUnderstanding() {
            return !polls.isEmpty() && polls.stream().allMatch(poll -> poll.type() == PollType.UNDERSTANDING)
                    && citations.keySet().stream().noneMatch(ref -> ref.type().equals("CHAT_MESSAGE"));
        }

        void writeTo(ObjectNode result) {
            var evidence = result.putArray("evidence");
            citations.forEach((ref, ids) -> {
                var citation = evidence.addObject().put("type", ref.type()).put("id", ref.id());
                if (!ids.isEmpty()) ids.forEach(citation.putArray("optionIds")::add);
            });
            var ranges = result.putArray("relatedWindows");
            for (var window : windows) {
                ranges.addObject().put("startAt", window.startAt().toString()).put("endAt", window.endAt().toString());
            }
            limitations.forEach(result.putArray("limitations")::add);
        }
    }
}
