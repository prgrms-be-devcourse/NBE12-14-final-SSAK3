import assert from 'node:assert/strict'
import test from 'node:test'
import { applyCounts, latestUnderstanding, percentage } from '../src/poll/understandingSummary.js'

const options = [
  { optionId: 1, content: '이해됨', understandingStatus: 'UNDERSTOOD' },
  { optionId: 2, content: '헷갈림', understandingStatus: 'CONFUSED' },
  { optionId: 3, content: '모르겠음', understandingStatus: 'NOT_UNDERSTOOD' },
]
const results = { pollId: 501, status: 'OPEN', totalParticipants: 0, respondedParticipants: 0,
  unrespondedParticipants: 0, responseRate: 0, options }

test('percentage rounds half up to one decimal and is 0 without a denominator', () => {
  assert.equal(percentage(6, 12), 50)
  assert.equal(percentage(4, 12), 33.3)
  assert.equal(percentage(2, 12), 16.7)
  assert.equal(percentage(1, 8), 12.5)
  assert.equal(percentage(1, 16), 6.3)
  assert.equal(percentage(0, 0), 0)
})

test('a results event refreshes counts and recalculates both denominators', () => {
  const next = applyCounts(results, {
    pollId: 501, totalParticipants: 20, respondedParticipants: 12, unrespondedParticipants: 8,
    counts: { 1: 6, 2: 4, 3: 2 },
  })
  assert.deepEqual(next.options.map(option => option.count), [6, 4, 2])
  assert.deepEqual(next.options.map(option => option.percentageOfResponses), [50, 33.3, 16.7])
  assert.deepEqual(next.options.map(option => option.percentageOfAllParticipants), [30, 20, 10])
  assert.equal(next.responseRate, 60)
  assert.equal(next.unrespondedParticipants, 8)
  assert.equal(next.status, 'OPEN')
})

test('unanswered people are not counted as any option', () => {
  const next = applyCounts(results, {
    pollId: 501, totalParticipants: 10, respondedParticipants: 0, unrespondedParticipants: 10, counts: {},
  })
  assert.deepEqual(next.options.map(option => option.count), [0, 0, 0])
  assert.equal(next.options[2].percentageOfAllParticipants, 0)
  assert.equal(next.unrespondedParticipants, 10)
})

test('an event for another poll leaves the screen as it is', () => {
  assert.equal(applyCounts(results, { pollId: 502, totalParticipants: 5, respondedParticipants: 5, counts: {} }), results)
  assert.equal(applyCounts(null, { pollId: 501 }), null)
})

test('the latest closed understanding check is picked, ignoring general polls', () => {
  const polls = [{ pollId: 3, type: 'GENERAL' }, { pollId: 1, type: 'UNDERSTANDING' }, { pollId: 2, type: 'UNDERSTANDING' }]
  assert.equal(latestUnderstanding(polls).pollId, 2)
  assert.equal(latestUnderstanding([{ pollId: 3, type: 'GENERAL' }]), null)
  assert.equal(latestUnderstanding(undefined), null)
})

test('a late event never overwrites a closed result', () => {
  const closed = { ...results, status: 'CLOSED', totalParticipants: 12, respondedParticipants: 9 }
  const late = { pollId: 501, totalParticipants: 12, respondedParticipants: 3, unrespondedParticipants: 9, counts: { 1: 3 } }
  assert.equal(applyCounts(closed, late), closed)
})
