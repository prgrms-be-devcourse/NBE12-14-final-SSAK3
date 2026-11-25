import test from 'node:test'
import assert from 'node:assert/strict'
import { createAudienceFeed, initialFeed } from '../src/ai/feed.js'
import { returnPath } from '../src/room/state.js'

const analysis = (id, status = 'COMPLETED', topics = true) => ({ analysisId: id,
  windowStartAt: new Date(id * 30000).toISOString(), windowEndAt: new Date((id + 1) * 30000).toISOString(),
  status, topicGroups: topics ? [{ topicGroupId: id, topic: `주제 ${id}`, summary: `요약 ${id}` }] : [] })
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
const settle = () => new Promise(resolve => setImmediate(resolve))

function harness(t) {
  const env = { analyses: [analysis(1)], state: initialFeed(), requests: [], source: null, list: null, hidden: false, redirects: 0 }
  env.feed = createAudienceFeed({ roomId: '10', hidden: () => env.hidden,
    onChange: state => { env.state = state }, onUnauthenticated: () => env.redirects++,
    request: async path => {
      env.requests.push(path)
      if (path === '/auth/me') return { userId: 1 }
      if (path === '/rooms/10') return { roomId: 10 }
      if (path.includes('/messages')) return env.source ? env.source(path) : { topicGroupId: Number(path.split('/').at(-2)), messages: [] }
      if (env.list) return env.list(path)
      return { roomId: 10, analyses: env.analyses }
    } })
  t.after(() => env.feed.dispose())
  return env
}

test('keeps last summary for empty, processing, failed windows and does not regress on late older completion', async t => {
  const e = harness(t); await e.feed.start()
  e.analyses = [analysis(3), analysis(2)]; await e.feed.start()
  assert.equal(e.state.current.analysisId, 3)
  for (const status of ['COMPLETED', 'PENDING', 'PROCESSING', 'FAILED']) {
    e.analyses = [analysis(4, status, false), analysis(2)]
    await e.feed.start()
    assert.equal(e.state.current.analysisId, 3)
    assert.equal(e.state.analyses[0].status, status)
    assert.deepEqual(e.state.visibleAnalyses.map(value => value.analysisId),
      status === 'COMPLETED' ? [2] : [4, 2])
  }
})

test('hides repeated empty completions while retaining their completion time separately from the last summary', async t => {
  const e = harness(t); await e.feed.start()
  const summaryTime = e.state.lastSummaryAt
  e.analyses = Array.from({ length: 10 }, (_, index) => analysis(11 - index, 'COMPLETED', false))
  await e.feed.start()
  assert.equal(e.state.current.analysisId, 1)
  assert.deepEqual(e.state.visibleAnalyses, [])
  assert.equal(e.state.lastSummaryAt, summaryTime)
  assert.equal(e.state.lastCompletedAt, e.analyses[0].windowEndAt)
  e.analyses = [analysis(12), ...e.analyses]
  await e.feed.start()
  assert.equal(e.state.current.analysisId, 12)
  assert.deepEqual(e.state.visibleAnalyses.map(value => value.analysisId), [12])
})

test('sources pin multiple cards, successful source reads are reused, explicit latest clears all expansion', async t => {
  const e = harness(t); e.analyses = [analysis(2), analysis(1)]; await e.feed.start()
  e.feed.toggleSource(1); e.feed.toggleSource(2); await settle()
  assert.deepEqual(e.state.openIds, [1, 2])
  e.analyses = [analysis(3), analysis(2)]; await e.feed.start()
  assert.equal(e.state.current.analysisId, 2)
  assert.equal(e.state.candidate.analysisId, 3)
  assert.equal(e.state.visibleAnalyses.at(-1).analysisId, 1)
  e.feed.toggleSource(1); e.feed.toggleSource(1); await settle()
  assert.equal(e.requests.filter(p => p.endsWith('/1/messages')).length, 1)
  e.feed.showLatest()
  assert.equal(e.state.current.analysisId, 3)
  assert.deepEqual(e.state.openIds, [])
})

test('clearing authorization invalidates a late list response and other in-flight source responses', async t => {
  const e = harness(t); await e.feed.start()
  const lateList = deferred(), lateSource = deferred()
  e.list = () => lateList.promise
  const poll = e.feed.start()
  e.source = path => path.includes('/2/') ? Promise.reject({ status: 403 }) : lateSource.promise
  e.feed.toggleSource(1); e.feed.toggleSource(2); await settle()
  assert.equal(e.state.fatal, 403)
  lateList.resolve({ roomId: 10, analyses: [analysis(9)] })
  lateSource.resolve({ topicGroupId: 1, messages: [{ content: 'private' }] })
  await poll; await settle()
  assert.equal(e.state.current, null)
  assert.deepEqual(e.state.sources, {})
  assert.equal(e.state.room, null)
})

test('disposing a room discards delayed responses without publishing or redirecting', async t => {
  const e = harness(t); await e.feed.start()
  const late = deferred(); e.list = () => late.promise
  const loading = e.feed.start(); const stateAtDispose = e.state
  e.feed.dispose(); late.resolve({ roomId: 10, analyses: [analysis(9)] }); await loading
  assert.equal(e.state, stateAtDispose)
  assert.equal(e.redirects, 0)
})

test('concurrent poll triggers do not overlap and a more request is queued after the active poll', async t => {
  const e = harness(t); await e.feed.start()
  const pending = deferred(); e.list = () => pending.promise
  const first = e.feed.start()
  await e.feed.start(); e.feed.more()
  assert.equal(e.requests.filter(p => p.includes('/analyses')).length, 2)
  e.list = path => ({ roomId: 10, analyses: [analysis(2)] })
  pending.resolve({ roomId: 10, analyses: [analysis(1)] }); await first
  // The queued load runs on the next timer turn after the first request completes.
  await new Promise(resolve => setTimeout(resolve, 20))
  assert.equal(e.requests.at(-1), '/rooms/10/ai/analyses?limit=50')
  assert.equal(e.state.limit, 50)
})

test('network failure stays stopped on tab return until explicit reconnect succeeds', async t => {
  const e = harness(t); await e.feed.start()
  e.list = () => Promise.reject(new Error('offline')); await e.feed.start()
  const count = e.requests.length
  e.feed.visibilityChanged(); await settle()
  assert.equal(e.requests.length, count)
  assert.equal(e.state.current.analysisId, 1)
  e.list = null; e.feed.reconnect(); await settle()
  assert.equal(e.state.error, false)
})

test('only the exact local AI return route is accepted', () => {
  assert.equal(returnPath('/dev/rooms/10/ai'), '/dev/rooms/10/ai')
  for (const path of ['https://evil.example/dev/rooms/10/ai', '//evil.example', '/dev/rooms/0/ai',
    '/dev/rooms/10/ai/extra', '/dev/rooms/10/ai?returnTo=https://evil.example', '/dev/rooms/../ai']) {
    assert.equal(returnPath(path), null)
  }
})
