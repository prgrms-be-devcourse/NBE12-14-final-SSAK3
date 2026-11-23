import assert from 'node:assert/strict'
import test from 'node:test'
import { selectGeneralPolls } from '../src/poll/pollResults.js'

const poll = (pollId, type = 'GENERAL', status = 'CLOSED') => ({ pollId, type, status })

test('only general polls are listed, understanding checks are left to their own panel', () => {
  const polls = selectGeneralPolls([poll(9, 'UNDERSTANDING', 'OPEN')], [poll(1), poll(2, 'UNDERSTANDING')])
  assert.deepEqual(polls.map(item => item.pollId), [1])
})

test('the open poll comes first and closed polls follow newest first', () => {
  const polls = selectGeneralPolls([poll(7, 'GENERAL', 'OPEN')], [poll(2), poll(5), poll(3)])
  assert.deepEqual(polls.map(item => item.pollId), [7, 5, 3, 2])
})

test('closed polls are capped to the most recent ones', () => {
  const closed = [1, 2, 3, 4, 5, 6, 7].map(id => poll(id))
  assert.deepEqual(selectGeneralPolls([], closed).map(item => item.pollId), [7, 6, 5, 4, 3])
  assert.deepEqual(selectGeneralPolls([], closed, 2).map(item => item.pollId), [7, 6])
})

test('missing lists give an empty result', () => {
  assert.deepEqual(selectGeneralPolls(undefined, undefined), [])
})
