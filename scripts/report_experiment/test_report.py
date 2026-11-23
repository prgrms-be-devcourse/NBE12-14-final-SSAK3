import copy
import json
from pathlib import Path
import unittest
from unittest.mock import patch
import urllib.error

import run
from build_schemas import contracts


class ReportExperimentTest(unittest.TestCase):
    def setUp(self):
        self.fixtures = run.read_json(run.ROOT / 'docs/experiments/report-scenarios.json')
        self.data = run.prepare('참석자 1 01:39\n전사 예시\n참석자 1 18:41\n가스 설명',
                                self.fixtures['scenarios'][0], 2939)

    def draft(self, data=None, identifier=1001):
        data = data or self.data
        record = next(r for r in data['evidenceRecords'] if r['id'] == identifier)
        return {'overview': {'mainInterests': [{'text': '사례를 요청한 의견이 있습니다.',
                'evidence': [{'type': record['type'], 'id': identifier}],
                'relatedWindows': copy.deepcopy(record['relatedWindows']), 'perspectives': []}],
                'reviewPoints': [], 'suggestions': []}}

    def assert_failure(self, draft, code, data=None):
        with self.assertRaises(run.ReportFailure) as caught:
            run.assemble(draft, data or self.data)
        self.assertEqual(code, caught.exception.code)

    def test_committed_schemas_match_generator(self):
        for name, schema in zip(('report-draft.schema.json', 'report-content.schema.json'), contracts()):
            self.assertEqual(schema, run.read_json(run.RESOURCES / name))

    def test_follow_up_contract_accepts_multiple_questions_and_unlimited_groups(self):
        groups = []
        for chat_id, title in enumerate(['수수료', '합의', '지갑', '계약'], start=1):
            quotes = ['비용은요?', '실패해도 내나요?'] if chat_id == 1 else [f'{title} 예시를 보나요?']
            questions = [{'requests': [{'messageId': chat_id, 'quote': quote, 'reexplanation': False}],
                          'answerSegmentIds': []} for quote in quotes]
            groups.append({'title': title, 'questions': questions, 'kind': 'PREPARATION',
                           'text': f'{title} 예시를 준비해 주세요.', 'segmentIds': []})
        draft = {'overview': {k: [] for k in run.GROUPS},
                 'followUp': {'reviewedChatIds': [1, 2, 3, 4], 'groups': groups}}
        run.schema_validate(draft, 'report-draft.schema.json')
        groups[0]['questions'][0]['requests'][0]['madeUpTime'] = '10:00'
        with self.assertRaises(run.ReportFailure):
            run.schema_validate(draft, 'report-draft.schema.json')

    def test_valid_reference_and_server_only_fields(self):
        draft = self.draft()
        final = run.assemble(draft, self.data)
        self.assertIn('limitations', final['overview']['mainInterests'][0])
        self.assertNotIn('limitations', draft['overview']['mainInterests'][0])
        draft['overview']['mainInterests'][0]['limitations'] = ['invented']
        self.assert_failure(draft, 'INVALID_REPORT_FORMAT')

    def test_nonexistent_reference(self):
        draft = self.draft()
        draft['overview']['mainInterests'][0]['evidence'][0]['id'] = 999999
        self.assert_failure(draft, 'INVALID_REPORT_EVIDENCE')

    def test_other_room_reference(self):
        draft = self.draft()
        next(r for r in self.data['evidenceRecords'] if r['id'] == 1001)['roomId'] = 2
        self.assert_failure(draft, 'INVALID_REPORT_EVIDENCE')

    def test_unrelated_window(self):
        draft = self.draft()
        draft['overview']['mainInterests'][0]['relatedWindows'] = [self.data['windows'][0]]
        self.assert_failure(draft, 'INVALID_REPORT_EVIDENCE')

    def test_three_item_limit(self):
        draft = self.draft()
        draft['overview']['mainInterests'] *= 4
        self.assert_failure(draft, 'INVALID_REPORT_FORMAT')

    def test_ai_summary_not_allowed_as_evidence(self):
        draft = self.draft()
        draft['overview']['mainInterests'][0]['evidence'][0]['type'] = 'AI_TOPIC_GROUP'
        self.assert_failure(draft, 'INVALID_REPORT_FORMAT')

    def test_opposing_union_and_preservation(self):
        data = run.prepare('참석자 1 01:39\n전사', self.fixtures['scenarios'][2], 2939)
        leaves = []
        for identifier in (1201, 1202):
            leaf = self.draft(data, identifier)['overview']['mainInterests'][0]
            del leaf['perspectives']; leaves.append(leaf)
        item = {'text': '실습과 설명 비중에 의견 차이가 있습니다.', 'evidence': [],
                'relatedWindows': [], 'perspectives': leaves}
        draft = {'overview': {g: [] for g in run.GROUPS}}
        draft['overview']['mainInterests'] = [item]
        final = run.assemble(draft, data)['overview']['mainInterests'][0]
        self.assertEqual(2, len(final['evidence']))
        self.assertEqual(1, len(final['relatedWindows']))
        self.assertEqual([1201, 1202], [p['evidence'][0]['id'] for p in final['perspectives']])
        item['perspectives'][1]['evidence'][0]['id'] = 999999
        self.assert_failure(draft, 'INVALID_REPORT_EVIDENCE', data)

    def test_limits_only_on_affected_item(self):
        data = run.prepare('참석자 1 01:39\n전사\n참석자 1 18:41\n삭제할 전사',
                           self.fixtures['scenarios'][3], 2939)
        self.assertFalse(any(r.get('text') == '삭제할 전사' for r in data['evidenceRecords']))
        draft = self.draft(data, 1301)
        early_chat = {**next(r for r in data['evidenceRecords'] if r['id'] == 1301),
                      'id': 1302, 'createdAt': run.iso(99), 'relatedWindows': [run.window(99, 2939)]}
        data['evidenceRecords'].append(early_chat)
        early = self.draft(data, 1302)['overview']['mainInterests'][0]
        draft['overview']['mainInterests'].append(early)
        final = run.assemble(draft, data)
        self.assertEqual(2, len(final['overview']['mainInterests'][0]['limitations']))
        self.assertEqual([], final['overview']['mainInterests'][1]['limitations'])
        self.assertEqual(3, len(final['dataLimitations']))

    def test_no_response_no_llm_path(self):
        data = run.prepare('참석자 1 01:39\n전사', {'chats': []}, 2939)
        self.assertFalse(run.has_audience(data))
        final = run.assemble({'overview': {g: [] for g in run.GROUPS}}, data)
        self.assertEqual({'status': 'NO_QUESTIONS', 'groups': []}, final['followUp'])
        self.assertIn('청중 반응 기록이 부족', final['dataLimitations'][-1])
        data['evidenceRecords'] = []
        self.assertIn('수집된 기록이 없어', run.limitations(data)[-1])

    def test_follow_up_preserves_unanswered_part_and_rejects_incomplete_coverage_locally(self):
        data = run.prepare('참석자 1 00:20\n실패한 거래도 수수료를 내요.',
                           {'chats': [{'id': 7, 'participantId': 'p1', 'offsetSeconds': 10,
                                       'text': '가스비는 어떻게 정해요? 실패해도 내나요?'}]}, 60)
        def question(quote, answer_ids):
            return {'requests': [{'messageId': 7, 'quote': quote, 'reexplanation': False}],
                    'answerSegmentIds': answer_ids}
        group = {'kind': 'PREPARATION', 'title': '가스비', 'text': '수수료 예시를 준비해 주세요.',
                 'segmentIds': [], 'questions': [question('가스비는 어떻게 정해요?', []),
                                                  question('실패해도 내나요?', [9001])]}
        draft = self.draft(data, 7)
        draft['followUp'] = {'reviewedChatIds': [7], 'groups': [group]}
        follow_up = run.assemble(draft, data)['followUp']
        self.assertEqual('CANDIDATES', follow_up['status'])
        self.assertEqual([[{'requests': group['questions'][0]['requests']}]],
                         [g['questions'] for g in follow_up['groups']])
        group['questions'] = [question('실패해도 내나요?', [9001])]
        self.assertEqual('ALL_ANSWERED', run.assemble(draft, data)['followUp']['status'])
        data['evidenceRecords'][0]['startAt'] = run.iso(10)
        self.assertEqual('CANDIDATES', run.assemble(draft, data)['followUp']['status'])
        draft['followUp']['reviewedChatIds'].clear()
        result = run.assemble(draft, data)
        self.assertEqual({'status': 'UNAVAILABLE', 'groups': []}, result['followUp'])
        self.assertTrue(result['overview']['mainInterests'])

    def test_malformed_answer_ref_falls_back_to_sorted_question_but_duplicate_draft_refs_invalidate_section(self):
        chats = [{'id': 2, 'participantId': 'p1', 'text': '다시요?', 'offsetSeconds': 12},
                 {'id': 1, 'participantId': 'p1', 'text': '비용은요?', 'offsetSeconds': 10}]
        data = run.prepare('참석자 1 00:20\n비용은 연산량으로 정해요.', {'chats': chats}, 60)
        draft = self.draft(data, 1)
        requests = [{'messageId': c['id'], 'quote': c['text'], 'reexplanation': c['id'] == 2} for c in chats]
        question = {'requests': requests, 'answerSegmentIds': [9001, 'wrong-type']}
        group = {'title': '비용', 'questions': [question], 'kind': 'DRAFT', 'text': '연산량을 확인하세요.',
                 'segmentIds': [9001, 9001]}
        draft['followUp'] = {'reviewedChatIds': [1, 2], 'groups': [group]}
        result = run.assemble(draft, data)['followUp']
        self.assertEqual('CANDIDATES', result['status'])
        self.assertEqual('비용은요?', result['groups'][0]['title'])
        self.assertEqual(list(reversed(requests)), result['groups'][0]['questions'][0]['requests'])
        self.assertEqual([], result['groups'][0]['segmentIds'])
        question['answerSegmentIds'] = []
        self.assertEqual('UNAVAILABLE', run.assemble(draft, data)['followUp']['status'])

    def test_closed_round_counts_and_no_carry(self):
        final = run.assemble(self.draft(), self.data)
        rounds = [r for row in final['timeline'] for r in row['understandingChecks']]
        self.assertEqual(1, len(rounds))
        self.assertEqual(2, rounds[0]['unrespondedParticipants'])
        self.assertEqual(0, rounds[0]['counts']['NOT_UNDERSTOOD'])
        self.assertFalse(rounds[0]['lowUnderstanding'])
        self.assertNotIn('responses', rounds[0])

    def test_last_response_only(self):
        fixture = copy.deepcopy(self.fixtures['scenarios'][0])
        fixture['understandingChecks'][0]['responses'].append({'id': 2103, 'participantId': 'p3',
            'respondedAtSeconds': 1190, 'understandingStatus': 'UNDERSTOOD'})
        data = run.prepare('참석자 1 01:39\n전사', fixture, 2939)
        result = next(r['result'] for r in data['evidenceRecords'] if r['id'] == 2001)
        self.assertEqual(2, result['respondedParticipants'])
        self.assertEqual(2, result['counts']['UNDERSTOOD'])

    def test_open_poll_rejected(self):
        draft = self.draft(identifier=2001)
        next(r for r in self.data['evidenceRecords'] if r['id'] == 2001)['result']['status'] = 'OPEN'
        self.assert_failure(draft, 'INVALID_REPORT_EVIDENCE')

    def test_stt_is_context_only(self):
        self.assert_failure(self.draft(identifier=9001), 'INVALID_REPORT_EVIDENCE')

    def test_poll_perspectives_keep_options_and_share_one_parent_reference(self):
        poll = copy.deepcopy(self.fixtures['scenarios'][0]['poll'])
        poll['question'] = '강연 속도를 어떻게 바꾸면 좋을까요?'
        poll['options'] = [{'optionId': 3011, 'content': '빠르게'}, {'optionId': 3012, 'content': '천천히'}]
        for response, answer in zip(poll['responses'], ('빠르게', '빠르게', '천천히')):
            response['answer'] = answer
        data = run.prepare('참석자 1 01:39\n전사', {'poll': poll}, 2939)
        leaf = self.draft(data, 3001)['overview']['mainInterests'][0]
        del leaf['perspectives']
        left, right = copy.deepcopy(leaf), copy.deepcopy(leaf)
        left['evidence'][0]['optionIds'] = [3011]
        right['evidence'][0]['optionIds'] = [3012]
        left['text'], right['text'] = '속도를 높여 달라는 응답', '속도를 늦춰 달라는 응답'
        draft = self.draft()
        draft['overview']['mainInterests'] = [{'text': '강연 속도에 의견이 갈렸습니다.', 'relatedWindows': [],
                                               'evidence': [], 'perspectives': [left, right]}]
        result = run.assemble(draft, data)['overview']['mainInterests'][0]
        self.assertEqual([{'type': 'POLL_RESULT', 'id': 3001}], result['evidence'])
        self.assertEqual([3012], result['perspectives'][1]['evidence'][0]['optionIds'])
        right['evidence'][0]['optionIds'] = [999]
        self.assert_failure(draft, 'INVALID_REPORT_EVIDENCE', data)

    def test_no_retry_and_no_error_body_leak(self):
        with patch('urllib.request.urlopen', side_effect=urllib.error.HTTPError('url', 403, 'secret error', {}, None)) as call:
            response = run.request_once(self.data, 'gpt-6-luna', 'test-key')
        self.assertEqual(1, call.call_count)
        self.assertEqual({'status': 'request_failed', 'httpStatus': 403}, response)

    def test_api_failure_and_bad_json_are_distinct(self):
        for response, code in [({'status': 'incomplete'}, 'LLM_REQUEST_FAILED'),
            ({'status': 'completed', 'output': [{'content': [{'type': 'output_text', 'text': 'invalid'}]}]}, 'INVALID_REPORT_FORMAT')]:
            with self.assertRaises(run.ReportFailure) as caught:
                run.evaluate_response(response, self.data)
            self.assertEqual(code, caught.exception.code)

    def test_cost_includes_billed_output_without_double_counting_reasoning(self):
        usage = {'input_tokens': 50000, 'output_tokens': 5000,
                 'output_tokens_details': {'reasoning_tokens': 3000}}
        self.assertEqual(0.0075, run.cost(usage, 'gpt-6-luna'))

    def test_cache_write_rates_replace_ordinary_input_rate(self):
        usage = {'input_tokens': 10000, 'output_tokens': 1000,
                 'input_tokens_details': {'cached_tokens': 1000, 'cache_write_tokens': 7000,
                     'cache_write_tokens_details': {'ttl_30m_tokens': 4000, 'ttl_12h_tokens': 3000}}}
        self.assertEqual(0.00196, run.cost(usage, 'gpt-6-luna'))
        del usage['input_tokens_details']['cache_write_tokens_details']
        self.assertIsNone(run.cost(usage, 'gpt-6-luna'))


if __name__ == '__main__':
    unittest.main()
