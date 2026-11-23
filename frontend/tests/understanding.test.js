import assert from 'node:assert/strict'
import test from 'node:test'
import { findOpenUnderstanding, isClosedEvent, remainingSeconds, withDeadline } from '../src/poll/understanding.js'

const poll = (pollId, type, status = 'OPEN') => ({ pollId, type, status })

test('only an open understanding poll is picked, ignoring general and closed polls', () => {
  assert.equal(findOpenUnderstanding([poll(1, 'GENERAL'), poll(2, 'UNDERSTANDING', 'CLOSED')]), null)
  assert.equal(findOpenUnderstanding([poll(1, 'GENERAL'), poll(3, 'UNDERSTANDING')]).pollId, 3)
  assert.equal(findOpenUnderstanding(undefined), null)
})

test('remaining time follows closesAt minus serverTime, not the device clock', () => {
  const opened = withDeadline({ pollId: 1, closesAt: '2026-11-17T01:21:00Z' }, '2026-11-17T01:20:00Z', 1000)
  assert.equal(remainingSeconds(opened, 1000), 60)
  assert.equal(remainingSeconds(opened, 1000 + 59_001), 1)
  assert.equal(remainingSeconds(opened, 1000 + 60_000), 0)
  assert.equal(remainingSeconds(opened, 1000 + 90_000), 0)
})

test('a poll that already passed closesAt or has an unreadable time shows zero seconds', () => {
  const late = withDeadline({ closesAt: '2026-11-17T01:20:00Z' }, '2026-11-17T01:20:30Z', 0)
  assert.equal(remainingSeconds(late, 0), 0)
  const broken = withDeadline({ closesAt: 'nope' }, '2026-11-17T01:20:00Z', 0)
  assert.equal(remainingSeconds(broken, 0), 0)
})

test('a close event only matches the poll that is on screen', () => {
  const event = { type: 'POLL_CLOSED', data: { pollId: 501 } }
  assert.equal(isClosedEvent(event, 501), true)
  assert.equal(isClosedEvent(event, 502), false)
  assert.equal(isClosedEvent({ type: 'POLL_CREATED', data: { pollId: 501 } }, 501), false)
  assert.equal(isClosedEvent(null, 501), false)
})
