"""Generate the committed, provider-neutral report contracts. No API calls."""
import copy
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RESOURCES = ROOT / 'backend/src/main/resources/report'


def obj(properties):
    return dict(type='object', properties=properties, required=list(properties), additionalProperties=False)


def array(items, **constraints):
    return dict(type='array', items=items, **constraints)


def contracts():
    text = {'type': 'string', 'minLength': 1}
    window = obj({'startAt': {'type': 'string', 'format': 'date-time'},
                  'endAt': {'type': 'string', 'format': 'date-time'}})
    evidence = obj({'type': {'enum': ['CHAT_MESSAGE', 'STT_SEGMENT', 'POLL_RESULT'], 'type': 'string'},
                    'id': {'type': 'integer', 'minimum': 1}})
    leaf = dict(text=text, evidence=array(evidence, minItems=1), relatedWindows=array(window, minItems=1))
    regular = obj({**leaf, 'perspectives': array(obj(leaf), maxItems=0)})
    opposing = obj({'text': text, 'evidence': array(evidence, maxItems=0),
                    'relatedWindows': array(window, maxItems=0),
                    'perspectives': array(obj(leaf), minItems=2)})
    draft = obj({'overview': obj({k: array({'anyOf': [regular, opposing]}, maxItems=3)
                                  for k in ('mainInterests', 'reviewPoints', 'suggestions')})})
    final_leaf = {**leaf, 'limitations': array(text)}
    final_item = {'anyOf': [
        obj({**final_leaf, 'perspectives': array(obj(final_leaf), maxItems=0)}),
        obj({**final_leaf, 'perspectives': array(obj(final_leaf), minItems=2)})]}
    # Timeline records are server-owned domain payloads; their API schemas remain in their domains.
    record_list = array({'type': 'object'})
    timeline = obj({**window['properties'], **{k: record_list for k in
        ('sttSegments', 'sttGaps', 'chatMessages', 'understandingChecks', 'audienceOpinions', 'polls')}})
    final = obj({'overview': obj({k: array(final_item, maxItems=3) for k in draft['properties']['overview']['properties']}),
                 'timeline': array(timeline), 'dataLimitations': array(text), 'evidencePolicy': text})
    return copy.deepcopy(draft), copy.deepcopy(final)


if __name__ == '__main__':
    RESOURCES.mkdir(parents=True, exist_ok=True)
    for name, schema in zip(('report-draft.schema.json', 'report-content.schema.json'), contracts()):
        (RESOURCES / name).write_text(json.dumps(schema, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
