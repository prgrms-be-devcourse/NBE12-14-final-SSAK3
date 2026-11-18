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
        early = self.draft(data, 9001)['overview']['mainInterests'][0]
        draft['overview']['mainInterests'].append(early)
        final = run.assemble(draft, data)
        self.assertEqual(2, len(final['overview']['mainInterests'][0]['limitations']))
        self.assertEqual([], final['overview']['mainInterests'][1]['limitations'])
        self.assertEqual(3, len(final['dataLimitations']))

    def test_no_response_no_llm_path(self):
        data = run.prepare('참석자 1 01:39\n전사', {'chats': []}, 2939)
        self.assertFalse(run.has_audience(data))
        final = run.assemble({'overview': {g: [] for g in run.GROUPS}}, data)
        self.assertIn('청중 반응 기록이 부족', final['dataLimitations'][-1])
        data['evidenceRecords'] = []
        self.assertIn('수집된 기록이 없어', run.limitations(data)[-1])

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
