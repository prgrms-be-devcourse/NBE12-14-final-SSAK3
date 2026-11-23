"""SCRUM-156 offline preparation, validation and single-attempt Responses API experiment.

This is an experiment harness, not the production report service or a DB migration.
"""
import argparse
import copy
from collections import Counter
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import time
import urllib.error
import urllib.request

from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[2]
RESOURCES = ROOT / 'backend/src/main/resources/report'
BASE = datetime(2026, 11, 18, tzinfo=timezone.utc)  # Synthetic experiment clock, not the recording date.
GROUPS = ('mainInterests', 'reviewPoints', 'suggestions')
COMMON_FAILURE = '보고서 생성에 실패했습니다.'


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def iso(seconds):
    return (BASE + timedelta(seconds=seconds)).isoformat().replace('+00:00', 'Z')


def window(seconds, duration):
    start = int(seconds // 60) * 60
    return {'startAt': iso(start), 'endAt': iso(min(start + 60, duration))}


def window_key(w):
    return w['startAt'], w['endAt']


def unique(values):
    return list({json.dumps(v, sort_keys=True): v for v in values}.values())


def overlap(a, b):
    return a['startAt'] < b['endAt'] and b['startAt'] < a['endAt']


class ReportFailure(Exception):
    def __init__(self, code):
        super().__init__(code)
        self.code = code


def schema_validate(value, name):
    validator = Draft202012Validator(read_json(RESOURCES / name), format_checker=FormatChecker())
    if next(validator.iter_errors(value), None) is not None:
        raise ReportFailure('INVALID_REPORT_FORMAT')


def prepare(source, scenario, duration):
    matches = list(re.finditer(r'^참석자\s+\d+\s+(\d{1,2}:\d{2}(?::\d{2})?)\s*$', source, re.M))
    if not matches:
        raise ValueError('No timestamped transcript segments')
    records, gaps, failed = [], [], []
    room_id = 1
    removed = scenario.get('removeTranscriptSegmentsStartingIn')
    for i, m in enumerate(matches):
        seconds = 0
        for part in m[1].split(':'):
            seconds = seconds * 60 + int(part)
        if not 0 <= seconds < duration:
            raise ValueError('Transcript timestamp outside lecture')
        if removed and removed['startSeconds'] <= seconds < removed['endSeconds']:
            continue
        end = matches[i + 1].start() if i + 1 < len(matches) else len(source)
        transcript = source[m.end():end].replace('clovanote.naver.com', '').strip()
        records.append({'type': 'STT_SEGMENT', 'id': 9001 + i, 'roomId': room_id,
                        'startAt': iso(seconds), 'endAt': None, 'text': transcript,
                        'relatedWindows': [window(seconds, duration)]})
    for c in scenario.get('chats', []):
        records.append({'type': 'CHAT_MESSAGE', 'id': c['id'], 'roomId': room_id,
                        'participantId': c['participantId'], 'createdAt': iso(c['offsetSeconds']),
                        'text': c['text'], 'relatedWindows': [window(c['offsetSeconds'], duration)]})
    polls = scenario.get('understandingChecks', []) + ([scenario['poll']] if scenario.get('poll') else [])
    for p in polls:
        if p['status'] != 'CLOSED':
            raise ValueError('REPORT_INPUT_NOT_READY')
        # Sort changes by server time, then retain the last response per participant.
        last = {r['participantId']: r for r in sorted(p['responses'], key=lambda r: r['respondedAtSeconds'])}
        counts = Counter(r.get('understandingStatus', r.get('answer')) for r in last.values())
        total = p.get('totalParticipants', 4)
        responded = len(last)
        if total < responded:
            raise ValueError('Invalid participant count')
        understanding = p['type'] == 'UNDERSTANDING'
        if understanding:
            counts = {s: counts[s] for s in ('UNDERSTOOD', 'CONFUSED', 'NOT_UNDERSTOOD')}
        result = {'type': p['type'], 'status': 'CLOSED', 'question': p.get('question', '지금 설명을 이해하셨나요?'),
                  'openedAt': iso(p['openedAtSeconds']), 'closedAt': iso(p['closedAtSeconds']),
                  'totalParticipants': total, 'respondedParticipants': responded,
                  'unrespondedParticipants': total - responded,
                  'responseRate': round(responded / total * 100, 1) if total else 0.0,
                  'counts': dict(counts)}
        result['percentagesOfResponses'] = {k: round(v / responded * 100, 1) if responded else 0.0 for k, v in counts.items()}
        result['percentagesOfAllParticipants'] = {k: round(v / total * 100, 1) if total else 0.0 for k, v in counts.items()}
        if understanding:
            lack = counts['CONFUSED'] + counts['NOT_UNDERSTOOD']
            ratio = round(lack / responded * 100, 1) if responded else 0.0
            result.update(lackCount=lack, lackRatio=ratio, lowUnderstanding=lack >= 3 and ratio >= 40)
        else:
            result['options'] = [{**option, 'count': counts[option['content']]} for option in p['options']]
        records.append({'type': 'POLL_RESULT', 'id': p.get('pollId', p.get('id')), 'roomId': room_id,
                        'result': result, 'relatedWindows': [window(p['openedAtSeconds'], duration)]})
    if scenario.get('gap'):
        g = scenario['gap']
        gaps.append({'id': g['id'], 'startAt': iso(g['startSeconds']), 'endAt': iso(g['endSeconds'])})
    for a in scenario.get('analysisWindows', []):
        if a['status'] == 'FAILED':
            failed.append({'startAt': iso(a['startSeconds']), 'endAt': iso(a['endSeconds'])})
    return {'roomId': room_id, 'syntheticAudience': True, 'durationSeconds': duration,
            'timestampNote': '실험 기준시각이며 원문에 종료 시각이 없어 STT는 시작 구간에만 연결함. 다음 발언 시작을 종료로 추정하지 않음.',
            'windows': [window(s, duration) for s in range(0, duration, 60)],
            'evidenceRecords': records, 'sttGaps': gaps, 'failedAnalysisWindows': failed,
            'analysisNote': '기존 AI 요약은 제공하지 않음. 준비 완료 상태는 실험 fixture 가정이며 운영 finalization 검증은 별도.'}


def has_audience(data):
    return any(r['type'] == 'CHAT_MESSAGE' or
               (r['type'] == 'POLL_RESULT' and r['result']['respondedParticipants'] > 0)
               for r in data['evidenceRecords'])


def limitations(data, windows=None):
    notes = []
    for key, message in [('sttGaps', '발언 기록이 없어 설명·답변 여부를 확인하기 어렵습니다.'),
                         ('failedAnalysisWindows', '청중 의견 AI 분석 실패. 남아 있는 원문 기록을 활용했습니다.')]:
        for interval in data[key]:
            if windows is None or any(overlap(interval, w) for w in windows):
                notes.append(f"{interval['startAt']} ~ {interval['endAt']}: {message}")
    if windows is None:
        notes.append(data['timestampNote'])
        if not has_audience(data):
            if any(r['type'] == 'STT_SEGMENT' for r in data['evidenceRecords']):
                notes.append('청중 반응 기록이 부족해 관심사와 보충 설명 지점을 판단하기 어렵습니다.')
            else:
                notes.append('수집된 기록이 없어 요약할 수 없습니다.')
    return unique(notes)


def assemble_follow_up(draft, data):
    chats = {r['id']: r for r in data['evidenceRecords']
             if r['roomId'] == data['roomId'] and r['type'] == 'CHAT_MESSAGE'}
    segments = {r['id']: r for r in data['evidenceRecords']
                if r['roomId'] == data['roomId'] and r['type'] == 'STT_SEGMENT'}
    if not chats:
        return {'status': 'NO_QUESTIONS', 'groups': []}

    def require(condition):
        if not condition:
            raise ReportFailure('INVALID_REPORT_EVIDENCE')

    def instant(timestamp):
        return datetime.fromisoformat(timestamp.replace('Z', '+00:00'))

    def fields(node, *names):
        require(isinstance(node, dict) and node.keys() == set(names))

    def request_order(request):
        message_id = request['messageId']
        return instant(chats[message_id]['createdAt']), message_id, request['quote']

    def order(group):
        requests = [r for q in group['questions'] for r in q['requests']]
        return not any(r['reexplanation'] for r in requests), min(map(request_order, requests))

    try:
        follow_up = draft.get('followUp')
        fields(follow_up, 'reviewedChatIds', 'groups')
        reviewed = follow_up['reviewedChatIds']
        require(isinstance(reviewed, list) and all(type(i) is int for i in reviewed))
        require(Counter(reviewed) == Counter(chats.keys()))
        require(isinstance(follow_up['groups'], list))
        pending = []
        initial_quotes, chains = set(), set()
        for group in follow_up['groups']:
            fields(group, 'title', 'questions', 'kind', 'text', 'segmentIds')
            require(isinstance(group['questions'], list) and group['questions'])
            survivors = []
            needs_fallback = False
            for question in group['questions']:
                fields(question, 'requests', 'answerSegmentIds')
                requests = question['requests']
                require(isinstance(requests, list) and requests)
                seen = set()
                for request in requests:
                    fields(request, 'messageId', 'quote', 'reexplanation')
                    message_id, quote = request['messageId'], request['quote']
                    require(type(message_id) is int and isinstance(quote, str) and type(request['reexplanation']) is bool)
                    key = (message_id, quote)
                    require(message_id in chats and quote.strip() and quote in chats[message_id]['text'])
                    require(key not in seen)
                    if not request['reexplanation']:
                        require(key not in initial_quotes)
                        initial_quotes.add(key)
                    seen.add(key)
                chain = frozenset(seen)
                require(chain not in chains)
                chains.add(chain)
                requests = sorted(requests, key=request_order)
                last = instant(chats[requests[-1]['messageId']]['createdAt'])
                answers = question['answerSegmentIds']
                require(isinstance(answers, list))
                if answers and all(type(i) is int and i in segments and instant(segments[i]['startAt']) > last
                                   for i in answers):
                    continue
                needs_fallback |= bool(answers)
                survivors.append({'requests': requests})
            if not survivors:
                continue
            if needs_fallback or len(survivors) != len(group['questions']):
                for q in survivors:
                    pending.append({'title': q['requests'][0]['quote'], 'questions': [q],
                                    'kind': 'PREPARATION', 'segmentIds': [],
                                    'text': '질문 원문을 확인하고 답변에 필요한 설명이나 예시를 준비해 주세요.'})
            else:
                require(all(isinstance(group[k], str) and group[k].strip() for k in ('title', 'text')))
                require(group['kind'] in ('DRAFT', 'PREPARATION'))
                ids = group['segmentIds']
                require(isinstance(ids, list) and all(type(i) is int and i in segments for i in ids))
                require(len(ids) == len(set(ids)) and bool(ids) == (group['kind'] == 'DRAFT'))
                pending.append({**group, 'questions': survivors})
        if pending:
            return {'status': 'CANDIDATES', 'groups': sorted(pending, key=order)}
        return {'status': 'ALL_ANSWERED' if chains else 'NO_QUESTIONS', 'groups': []}
    except ReportFailure:
        return {'status': 'UNAVAILABLE', 'groups': []}


def assemble(draft, data):
    if not isinstance(draft, dict):
        raise ReportFailure('INVALID_REPORT_FORMAT')
    # Keep the original overview validation strict even when the other section is unusable.
    schema_validate({**draft, 'followUp': {'reviewedChatIds': [], 'groups': []}}, 'report-draft.schema.json')
    records = {(r['type'], r['id']): r for r in data['evidenceRecords']}
    allowed_windows = {window_key(w) for w in data['windows']}

    summarized_checks = set()

    def validate_leaf(leaf, group_name, stance=False):
        evidence_windows = set()
        audience_types, mixed_checks = set(), set()
        for ref in leaf['evidence']:
            record = records.get((ref['type'], ref['id']))
            if record is None or record['roomId'] != data['roomId']:
                raise ReportFailure('INVALID_REPORT_EVIDENCE')
            if record['type'] == 'POLL_RESULT':
                result = record['result']
                if result['status'] != 'CLOSED' or result['respondedParticipants'] == 0:
                    raise ReportFailure('INVALID_REPORT_EVIDENCE')
                if stance and result['type'] == 'GENERAL' and not ref.get('optionIds'):
                    raise ReportFailure('INVALID_REPORT_EVIDENCE')
                if 'optionIds' in ref:
                    eligible = {o['optionId'] for o in result.get('options', []) if o['count'] > 0}
                    if result['type'] != 'GENERAL' or not set(ref['optionIds']) <= eligible:
                        raise ReportFailure('INVALID_REPORT_EVIDENCE')
                audience_types.add(result['type'])
                if result['type'] == 'UNDERSTANDING' and sum(n > 0 for n in result['counts'].values()) > 1:
                    mixed_checks.add(record['id'])
            elif record['type'] == 'CHAT_MESSAGE':
                audience_types.add('CHAT_MESSAGE')
            evidence_windows.update(window_key(w) for w in record['relatedWindows'])
        if audience_types == {'UNDERSTANDING'}:
            if stance or (mixed_checks and (group_name != 'reviewPoints' or mixed_checks & summarized_checks)):
                raise ReportFailure('INVALID_REPORT_EVIDENCE')
            summarized_checks.update(mixed_checks)
        selected = {window_key(w) for w in leaf['relatedWindows']}
        if not audience_types or selected != evidence_windows or not selected <= allowed_windows:
            raise ReportFailure('INVALID_REPORT_EVIDENCE')
        if not leaf['text'].strip():
            raise ReportFailure('INVALID_REPORT_FORMAT')
        leaf['evidence'] = unique(leaf['evidence'])
        leaf['relatedWindows'] = sorted(unique(leaf['relatedWindows']), key=window_key)
        leaf['limitations'] = limitations(data, leaf['relatedWindows'])

    overview = copy.deepcopy(draft['overview'])
    for group_name, group in overview.items():
        for item in group:
            if not item['text'].strip():
                raise ReportFailure('INVALID_REPORT_FORMAT')
            if item['perspectives']:
                used_choices = set()
                for perspective in item['perspectives']:
                    validate_leaf(perspective, group_name, stance=True)
                    choices = {(e['id'], option_id) for e in perspective['evidence']
                               for option_id in e.get('optionIds', [])}
                    if choices & used_choices:
                        raise ReportFailure('INVALID_REPORT_EVIDENCE')
                    used_choices.update(choices)
                item['evidence'] = unique([{'type': e['type'], 'id': e['id']}
                                           for p in item['perspectives'] for e in p['evidence']])
                item['relatedWindows'] = sorted(unique([w for p in item['perspectives'] for w in p['relatedWindows']]), key=window_key)
                item['limitations'] = unique([s for p in item['perspectives'] for s in p['limitations']])
            else:
                validate_leaf(item, group_name)
    timeline = []
    for w in data['windows']:
        row = {**w, **{k: [] for k in ('sttSegments', 'sttGaps', 'chatMessages', 'understandingChecks', 'audienceOpinions', 'polls')}}
        for r in data['evidenceRecords']:
            if w not in r['relatedWindows']:
                continue
            if r['type'] == 'STT_SEGMENT':
                row['sttSegments'].append({'segmentId': r['id'], 'transcript': r['text'], 'startAt': r['startAt'], 'endAt': r['endAt']})
            elif r['type'] == 'CHAT_MESSAGE':
                row['chatMessages'].append({'messageId': r['id'], 'text': r['text'], 'createdAt': r['createdAt']})
            else:
                key = 'understandingChecks' if r['result']['type'] == 'UNDERSTANDING' else 'polls'
                row[key].append({'pollId': r['id'], **r['result']})
        row['sttGaps'] = [g for g in data['sttGaps'] if overlap(g, w)]
        row['audienceOpinions'] = [{'status': 'FAILED', **a} for a in data['failedAnalysisWindows'] if overlap(a, w)]
        timeline.append(row)
    result = {'overview': overview, 'followUp': assemble_follow_up(draft, data),
              'timeline': timeline, 'dataLimitations': limitations(data),
              'evidencePolicy': '동일 시간대의 관찰은 인과관계를 의미하지 않습니다. AI 요약은 원문 및 서버 집계와 구분합니다.'}
    schema_validate(result, 'report-content.schema.json')
    return result


def api_key():
    key = os.environ.get('OPENAI_API_KEY')
    if key:
        return key
    path = ROOT / '.env'
    if path.exists():
        for line in path.read_text(encoding='utf-8-sig').splitlines():
            name, sep, value = line.partition('=')
            if sep and name.strip() == 'OPENAI_API_KEY':
                return value.strip().strip('"\'')
    return None


def request_once(data, model, key):
    model_input = {**data, 'evidenceRecords': [r for r in data['evidenceRecords']
                   if r['type'] != 'POLL_RESULT' or r['result']['respondedParticipants'] > 0]}
    body = {'model': model, 'store': False, 'reasoning': {'effort': 'low'},
            'service_tier': 'default',
            'instructions': (RESOURCES / 'report-system-prompt.txt').read_text(encoding='utf-8'),
            'input': json.dumps(model_input, ensure_ascii=False),
            'text': {'format': {'type': 'json_schema', 'name': 'lecture_report', 'strict': True,
                                'schema': read_json(RESOURCES / 'report-draft.schema.json')}}}
    request = urllib.request.Request('https://api.openai.com/v1/responses',
        data=json.dumps(body).encode('utf-8'),
        headers={'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json'}, method='POST')
    # urllib performs no application retries. Never print response error bodies or credentials.
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        return {'status': 'request_failed', 'httpStatus': error.code}
    except (urllib.error.URLError, TimeoutError, OSError):
        return {'status': 'request_failed', 'httpStatus': None}


def evaluate_response(response, data):
    if response.get('status') != 'completed':
        raise ReportFailure('LLM_REQUEST_FAILED')
    chunks = []
    for output in response.get('output', []):
        for content in output.get('content', []):
            if content.get('type') == 'refusal':
                raise ReportFailure('LLM_REQUEST_FAILED')
            if content.get('type') == 'output_text':
                chunks.append(content['text'])
    try:
        draft = json.loads(''.join(chunks))
    except (ValueError, TypeError):
        raise ReportFailure('INVALID_REPORT_FORMAT') from None
    return draft, assemble(draft, data)


def cost(usage, model):
    if model != 'gpt-6-luna' or not usage:
        return None
    inp, out = usage['input_tokens'], usage['output_tokens']
    details = usage.get('input_tokens_details', {})
    cached = details.get('cached_tokens', 0)
    writes = details.get('cache_write_tokens', 0)
    write_details = details.get('cache_write_tokens_details', {})
    short = write_details.get('ttl_30m_tokens', 0)
    long = write_details.get('ttl_12h_tokens', 0)
    ordinary = inp - cached - writes
    if short + long != writes or min(ordinary, cached, short, long, out) < 0:
        return None  # Unknown billing breakdown must not become an understated estimate.
    # Standard pricing verified 2026-11-18. Cache writes replace ordinary input pricing.
    # Output already includes reasoning tokens; never charge those twice.
    input_rate, output_rate = (0.2, 0.75) if inp > 272000 else (0.1, 0.5)
    return round((ordinary * input_rate + cached * input_rate * 0.1
                  + short * input_rate * 1.25 + long * input_rate * 2.5
                  + out * output_rate) / 1e6, 8)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True, type=Path)
    parser.add_argument('--output', type=Path, required=True, help='New directory; an existing run is never overwritten or retried')
    parser.add_argument('--execute', action='store_true', help='Make one paid API request per eligible scenario')
    parser.add_argument('--model', default='gpt-6-luna')
    args = parser.parse_args()
    key = api_key() if args.execute else None
    if args.execute and not key:
        parser.error('OPENAI_API_KEY missing; add it to root .env (never pass it on the command line)')
    fixtures = read_json(ROOT / 'docs/experiments/report-scenarios.json')
    source = args.source.read_text(encoding='utf-8-sig')
    prepared = [(s, prepare(source, s, fixtures['source']['durationSeconds'])) for s in fixtures['scenarios']]
    args.output.mkdir(parents=True, exist_ok=False)
    summary = {'model': args.model, 'reasoningEffort': 'low', 'syntheticAudience': True,
               'sourceSha256': hashlib.sha256(source.encode()).hexdigest(),
               'promptSha256': hashlib.sha256((RESOURCES / 'report-system-prompt.txt').read_bytes()).hexdigest(),
               'executed': args.execute, 'results': []}
    for scenario, data in prepared:
        path = args.output / scenario['id']; path.mkdir()
        write_json(path / 'input.json', data)
        result = {'scenario': scenario['id'], 'status': 'PREPARED', 'humanReview': 'PENDING', 'checks': scenario['checks']}
        if not has_audience(data):
            report = assemble({'overview': {g: [] for g in GROUPS}}, data)
            write_json(path / 'report.json', report)
            result.update(status='COMPLETED', skippedLlm=True, estimatedCostUsd=0)
        elif args.execute:
            write_json(path / 'attempt.json', {'status': 'STARTED', 'attempts': 1})
            started = time.monotonic()
            response = request_once(data, args.model, key)
            usage = response.get('usage')
            result.update(elapsedSeconds=round(time.monotonic() - started, 2), usage=usage,
                          estimatedCostUsd=cost(usage, args.model), httpStatus=response.get('httpStatus'))
            try:
                draft, report = evaluate_response(response, data)
                write_json(path / 'draft.json', draft)
                write_json(path / 'report.json', report)
                result['status'] = 'COMPLETED'
            except ReportFailure as error:
                result.update(status='FAILED', internalFailure=error.code, message=COMMON_FAILURE)
            write_json(path / 'attempt.json', {'status': result['status'], 'attempts': 1})
        summary['results'].append(result)
        write_json(args.output / 'summary.json', summary)
        print(scenario['id'] + ': ' + result['status'], flush=True)


if __name__ == '__main__':
    main()
