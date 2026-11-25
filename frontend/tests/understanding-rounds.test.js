import assert from 'node:assert/strict'
import test from 'node:test'
import { bars, describe, formatRatio, plot, roundLabel, roundTime, shares, thresholdY } from '../src/poll/understandingRounds.js'

const round = (counts, extra = {}) => ({
  pollId: 1, openedAt: '2026-11-17T01:20:00Z', closedAt: '2026-11-17T01:21:00Z', totalParticipants: 20,
  respondedParticipants: Object.values(counts).reduce((sum, value) => sum + value, 0),
  counts: { UNDERSTOOD: 0, CONFUSED: 0, NOT_UNDERSTOOD: 0, ...counts },
  lackCount: 0, lackRatio: 0, lowUnderstanding: false, ...extra,
})

test('shares use respondents as the denominator and leave unanswered people out', () => {
  assert.deepEqual(shares(round({ UNDERSTOOD: 6, CONFUSED: 4, NOT_UNDERSTOOD: 2 })),
    { UNDERSTOOD: 50, CONFUSED: 33.3, NOT_UNDERSTOOD: 16.7 })
  assert.deepEqual(shares(round({})), { UNDERSTOOD: 0, CONFUSED: 0, NOT_UNDERSTOOD: 0 })
})

test('a bar stacks understood at the bottom and fills the plot exactly', () => {
  const [bar] = bars([round({ UNDERSTOOD: 6, CONFUSED: 4, NOT_UNDERSTOOD: 2 })])
  assert.deepEqual(bar.segments.map(segment => segment.status), ['UNDERSTOOD', 'CONFUSED', 'NOT_UNDERSTOOD'])
  assert.equal(bar.segments[0].y + bar.segments[0].height, plot.top + plot.height)
  assert.equal(bar.segments[1].y + bar.segments[1].height, bar.segments[0].y)
  assert.equal(bar.segments[2].y, plot.top)
  const total = bar.segments.reduce((sum, segment) => sum + segment.height, 0)
  assert.ok(Math.abs(total - plot.height) < 0.2)
})

test('options nobody chose get no segment and a round without answers has no bar body', () => {
  const [partial, empty] = bars([round({ UNDERSTOOD: 3, NOT_UNDERSTOOD: 1 }), round({})])
  assert.deepEqual(partial.segments.map(segment => segment.status), ['UNDERSTOOD', 'NOT_UNDERSTOOD'])
  assert.deepEqual(empty.segments, [])
})

test('bars are spread across the plot and stay inside it', () => {
  const list = bars([round({ UNDERSTOOD: 1 }), round({ UNDERSTOOD: 1 }), round({ UNDERSTOOD: 1 })])
  assert.ok(list[0].x >= plot.left && list[2].x + list[2].width <= plot.right)
  assert.ok(list[0].centerX < list[1].centerX && list[1].centerX < list[2].centerX)
  const crowded = bars(Array.from({ length: 30 }, () => round({ UNDERSTOOD: 1 })))
  assert.ok(crowded.every(bar => bar.x >= plot.left && bar.x + bar.width <= plot.right && bar.width > 0))
  assert.deepEqual(bars([]), [])
})

test('the threshold line sits where a lack share of that percentage begins', () => {
  assert.equal(thresholdY(40), 64)
  assert.equal(thresholdY(50), 80)
  assert.equal(thresholdY(100), plot.height)
})

test('labels follow the server order and show Korea time', () => {
  assert.equal(roundLabel(0), '1회')
  assert.equal(roundLabel(4), '5회')
  assert.equal(roundTime(round({})), '10:20')
  assert.equal(formatRatio(62.5), '62.5%')
  assert.equal(formatRatio(0), '0.0%')
})

test('the spoken summary names the rounds above the threshold', () => {
  const rounds = [round({ UNDERSTOOD: 9 }), round({ UNDERSTOOD: 2, CONFUSED: 4 }, { lowUnderstanding: true }),
    round({ UNDERSTOOD: 2, CONFUSED: 4 }, { lowUnderstanding: true })]
  assert.match(describe(rounds, 40), /2회, 3회에서 이해 부족 기준\(40%\)을 넘었습니다/)
  assert.match(describe([rounds[0]], 40), /넘은 라운드는 없습니다/)
  assert.match(describe(rounds, 40), /3개 라운드/)
})
