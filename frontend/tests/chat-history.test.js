import assert from 'node:assert/strict'
import test from 'node:test'
import { mergeMessages, readHistory } from '../src/chat/history.js'

const message = messageId => ({ messageId })

test('a reconnect pages back to the last REST snapshot even if later broadcasts arrived first', async () => {
  const calls = []
  const pages = new Map([
    [null, { messages: [8, 10].map(message), hasNext: true, nextCursor: 8 }],
    [8, { messages: [6, 7].map(message), hasNext: true, nextCursor: 6 }],
    [6, { messages: [3, 5].map(message), hasNext: true, nextCursor: 3 }],
  ])
  const fresh = await readHistory(async cursor => { calls.push(cursor); return pages.get(cursor) }, 5, () => true)
  const current = [2, 3, 5, 10, 11].map(message)
  assert.deepEqual(calls, [null, 8, 6])
  assert.deepEqual(mergeMessages(current, fresh).map(item => item.messageId), [2, 3, 5, 6, 7, 8, 10, 11])
})

test('a late page from an obsolete connection does not update the new room', async () => {
  let active = true
  const result = await readHistory(async () => {
    active = false
    return { messages: [message(100)], hasNext: true, nextCursor: 100 }
  }, 0, () => active)
  assert.deepEqual(result, [])
})
