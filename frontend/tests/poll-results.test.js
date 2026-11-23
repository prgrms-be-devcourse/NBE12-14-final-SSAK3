import assert from 'node:assert/strict'
import test from 'node:test'
import { selectPolls } from '../src/poll/pollResults.js'

const poll = (pollId, type = 'GENERAL', status = 'CLOSED') => ({ pollId, type, status })
const ids = polls => polls.map(item => item.pollId)

test('only polls of the requested type are listed, so each panel shows its own kind', () => {
  const open = [poll(9, 'UNDERSTANDING', 'OPEN')]
  const closed = [poll(1), poll(2, 'UNDERSTANDING')]
  assert.deepEqual(ids(selectPolls(open, closed, 'GENERAL')), [1])
  assert.deepEqual(ids(selectPolls(open, closed, 'UNDERSTANDING')), [9, 2])
})

test('the open poll comes first and closed polls follow newest first', () => {
  const polls = selectPolls([poll(7, 'GENERAL', 'OPEN')], [poll(2), poll(5), poll(3)], 'GENERAL')
  assert.deepEqual(ids(polls), [7, 5, 3, 2])
})

test('closed polls are capped only when a limit is given', () => {
  const closed = [1, 2, 3, 4, 5, 6, 7].map(id => poll(id))
  assert.deepEqual(ids(selectPolls([], closed, 'GENERAL', 3)), [7, 6, 5])
  assert.deepEqual(ids(selectPolls([], closed, 'GENERAL')), [7, 6, 5, 4, 3, 2, 1])
})

test('missing lists give an empty result', () => {
  assert.deepEqual(selectPolls(undefined, undefined, 'GENERAL'), [])
})
