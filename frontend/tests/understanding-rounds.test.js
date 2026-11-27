import assert from 'node:assert/strict'
import test from 'node:test'

import {
  barSegments,
  formatRatio,
  mergeClosedRounds,
  roundFromPoll,
  roundLabel,
  roundTime,
  shares,
} from '../src/poll/understandingRounds.js'

const round = (counts, extra = {}) => ({
  pollId: 1,
  openedAt: '2026-11-17T01:20:00Z',
  closedAt: '2026-11-17T01:21:00Z',
  totalParticipants: 20,

  respondedParticipants: Object.values(counts)
      .reduce((sum, value) => sum + value, 0),

  counts: {
    UNDERSTOOD: 0,
    CONFUSED: 0,
    NOT_UNDERSTOOD: 0,
    ...counts,
  },

  lackCount: 0,
  lackRatio: 0,
  lowUnderstanding: false,

  ...extra,
})

const poll = (pollId, status = 'CLOSED') => ({
  pollId,
  status,
  type: 'UNDERSTANDING',
  createdAt: '2026-11-17T01:20:00Z',
  closedAt: '2026-11-17T01:21:00Z',
  totalParticipants: 10,
  respondedParticipants: 5,

  options: [
    {
      understandingStatus: 'UNDERSTOOD',
      count: 2,
    },
    {
      understandingStatus: 'CONFUSED',
      count: 2,
    },
    {
      understandingStatus: 'NOT_UNDERSTOOD',
      count: 1,
    },
  ],
})

test('응답자 기준으로 이해도 비율을 계산한다', () => {
  assert.deepEqual(
      shares(round({
        UNDERSTOOD: 6,
        CONFUSED: 4,
        NOT_UNDERSTOOD: 2,
      })),
      {
        UNDERSTOOD: 50,
        CONFUSED: 33.3,
        NOT_UNDERSTOOD: 16.7,
      },
  )
})

test('미응답자는 이해도 비율의 분모에 포함하지 않는다', () => {
  const values = shares(round(
      { UNDERSTOOD: 5 },
      { totalParticipants: 20 },
  ))

  assert.equal(values.UNDERSTOOD, 100)
})

test('누적 막대의 실제 너비 합은 100%다', () => {
  const segments = barSegments(round({
    UNDERSTOOD: 1,
    CONFUSED: 1,
    NOT_UNDERSTOOD: 1,
  }))

  assert.equal(segments.length, 3)

  const totalWidth = segments.reduce(
      (sum, segment) => sum + segment.width,
      0,
  )

  assert.ok(Math.abs(totalWidth - 100) < 0.00001)
})

test('선택한 사람이 없는 항목은 막대에서 제외한다', () => {
  const segments = barSegments(round({
    UNDERSTOOD: 4,
    NOT_UNDERSTOOD: 1,
  }))

  assert.deepEqual(
      segments.map(segment => segment.status),
      ['UNDERSTOOD', 'NOT_UNDERSTOOD'],
  )

  assert.equal(segments[0].value, 80)
  assert.equal(segments[1].value, 20)
})

test('모든 사람이 이해됨을 선택하면 막대 전체를 채운다', () => {
  const segments = barSegments(
      round({ UNDERSTOOD: 5 }),
  )

  assert.equal(segments.length, 1)
  assert.equal(segments[0].width, 100)
  assert.equal(segments[0].value, 100)
})

test('응답자가 없는 회차는 빈 막대를 반환한다', () => {
  assert.deepEqual(
      barSegments(round({})),
      [],
  )

  assert.deepEqual(
      shares(round({})),
      {
        UNDERSTOOD: 0,
        CONFUSED: 0,
        NOT_UNDERSTOOD: 0,
      },
  )
})

test('회차 번호와 시간을 한국 시간으로 표시한다', () => {
  assert.equal(roundLabel(0), '1회차')
  assert.equal(roundLabel(4), '5회차')
  assert.equal(roundTime(round({})), '10:20')
  assert.equal(roundTime({ openedAt: null }), '—')

  assert.equal(formatRatio(62.5), '62.5%')
  assert.equal(formatRatio(0), '0.0%')
})

test('라운드 조회 실패 시 마감된 투표 결과를 활용한다', () => {
  const converted = roundFromPoll(poll(1))

  assert.equal(converted.respondedParticipants, 5)
  assert.equal(converted.lackCount, 3)
  assert.equal(converted.lackRatio, 60)
  assert.equal(converted.lowUnderstanding, null)

  assert.deepEqual(
      converted.counts,
      {
        UNDERSTOOD: 2,
        CONFUSED: 2,
        NOT_UNDERSTOOD: 1,
      },
  )
})

test('서버 라운드 결과를 우선 사용하고 중복 표시하지 않는다', () => {
  const confirmed = round(
      {
        UNDERSTOOD: 3,
        CONFUSED: 2,
      },
      {
        pollId: 1,
        lackRatio: 40,
        lowUnderstanding: true,
      },
  )

  const rounds = mergeClosedRounds(
      [poll(1)],
      [confirmed],
  )

  assert.equal(rounds.length, 1)
  assert.deepEqual(rounds[0], confirmed)
})

test('진행 중인 체크를 제외하고 마감 회차를 순서대로 표시한다', () => {
  const rounds = mergeClosedRounds(
      [
        poll(3, 'OPEN'),
        poll(2),
        poll(1),
      ],
      [],
  )

  assert.deepEqual(
      rounds.map(item => item.pollId),
      [1, 2],
  )
})