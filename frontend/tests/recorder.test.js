import test from 'node:test'
import assert from 'node:assert/strict'
import { Recorder } from '../src/stt/recorder.js'
import { ApiError } from '../src/stt/api.js'
import { TranscriptTracker, createSpeech } from '../src/stt/speech.js'

const tick = () => new Promise(resolve => setImmediate(resolve))
const instant = '2026-11-18T01:00:10.123Z'
const segment = { transcript: '트랜잭션', segmentStartAt: instant, segmentEndAt: instant }
function gate() {
  let release
  const promise = new Promise(resolve => { release = resolve })
  return { promise, release }
}

function fixture(saved) {
  let snapshot = saved
  const clock = { elapsed: 0, serverTime: instant }
  let sequence = 0
  let callbacks
  let active = false
  let storageFailure = false
  const calls = []
  const timers = new Map()
  const api = {
    me: async () => ({ userId: 1 }), records: async () => ({ gaps: [], segments: [] }),
    start: async key => {
      calls.push(['start', key])
      return { sttSessionId: 7, status: 'RECORDING', canRecord: true, startedAt: instant, serverTime: clock.serverTime }
    },
    send: async (id, item) => { calls.push(['send', structuredClone(item)]); return { gapId: 9 } },
    stop: async (id, reason) => { calls.push(['stop', reason]); return { sttSessionId: id, status: reason === 'ERROR' ? 'ERROR' : 'STOPPED', canRecord: false } },
  }
  const store = { load: async () => structuredClone(snapshot), save: async value => {
    if (storageFailure) throw new Error('quota')
    snapshot = structuredClone(value)
  } }
  const recorder = new Recorder({ userId: 1, roomId: 10, api, store,
    acquire: async () => () => calls.push(['release']), uuid: () => `id-${++sequence}`, monotonicNow: () => clock.elapsed,
    schedule: (fn, delay) => { const id = ++sequence; timers.set(id, { fn, delay }); return id },
    cancel: id => timers.delete(id),
    speechFactory: options => {
      callbacks = options
      return { start: async () => { active = true; calls.push(['mic']) },
        stop: async () => { active = false }, abort: () => { active = false } }
    },
  })
  return { recorder, api, clock, store, calls, timers, emit: value => callbacks.onResult(value), end: error => callbacks.onEnd(error),
    active: () => active, snapshot: () => snapshot, failStorage: () => { storageFailure = true } }
}

test('interim first observation is retained; repeated finals are not emitted twice', () => {
  const tracker = new TranscriptTracker()
  const result = (text, final) => Object.assign([{ transcript: text }], { isFinal: final })
  assert.equal(tracker.accept({ results: [result('트랜', false)] }, instant).finals.length, 0)
  const end = '2026-11-18T01:00:12.456Z'
  const final = tracker.accept({ results: [result('트랜잭션', true)] }, end).finals[0]
  assert.equal(final.segmentStartAt, instant)
  assert.equal(final.segmentEndAt, end)
  assert.equal(tracker.accept({ results: [result('트랜잭션', true)] }, end).finals.length, 0)
  const direct = tracker.accept({ results: [result('트랜잭션', true), result('다음', true)] }, end).finals[0]
  assert.equal(direct.segmentStartAt, direct.segmentEndAt)
})

test('server confirmation precedes microphone and key is saved before start request', async () => {
  const f = fixture()
  await f.recorder.init()
  f.api.start = async key => {
    assert.equal(f.snapshot().startKey, key)
    assert.equal(f.active(), false)
    return { sttSessionId: 7, status: 'RECORDING', canRecord: true, serverTime: instant }
  }
  await f.recorder.start()
  assert.equal(f.active(), true)
  await f.recorder.dispose()
})

test('an unusable server clock keeps the microphone off until the same session supplies a valid sample', async () => {
  const f = fixture()
  await f.recorder.init()
  for (const unusable of [undefined, 'invalid']) {
    f.clock.serverTime = unusable
    await f.recorder.start()
    assert.equal(f.active(), false)
    assert.equal(f.recorder.getSnapshot().phase, 'paused')
  }
  assert.equal(f.calls.some(([kind]) => kind === 'stop'), false)
  const startKey = f.snapshot().startKey
  assert.deepEqual(f.calls.filter(([kind]) => kind === 'start'), [['start', startKey], ['start', startKey]])
  await f.recorder.dispose()
  const restored = fixture(f.snapshot())
  await restored.recorder.init()
  await restored.recorder.draining
  assert.equal(restored.active(), false)
  assert.equal(restored.calls.some(([kind]) => kind === 'send'), false)
  await restored.recorder.start()
  assert.equal(restored.active(), true)
  assert.deepEqual(restored.calls.filter(([kind]) => kind === 'start'), [['start', startKey], ['start', startKey]])
  await restored.recorder.dispose()
})

test('lost start response retries the same startKey without opening the microphone', async () => {
  const f = fixture()
  await f.recorder.init()
  const keys = []
  f.api.start = async key => { keys.push(key); throw new TypeError('network') }
  await f.recorder.start()
  await f.recorder.start()
  assert.equal(keys[0], keys[1])
  assert.equal(f.active(), false)
  await f.recorder.dispose()
})

test('room end resolves a lost STT start using the original key and closes it without opening the mic', async () => {
  const f = fixture()
  const start = f.api.start
  await f.recorder.init()
  f.api.start = async () => { throw new TypeError('response lost') }
  await f.recorder.start()
  const key = f.snapshot().startKey
  assert.ok(key)
  f.api.start = start
  assert.equal(await f.recorder.finishForRoomEnd(), true)
  assert.deepEqual(f.calls, [['start', key], ['stop', 'MANUAL_STOP']])
  await f.recorder.dispose()
})

test('lack of Web Locks never permits room end over locally pending speech', async () => {
  const f = fixture({ version: 1, queue: [{ kind: 'segment' }], archived: [] })
  f.recorder.acquire = async () => { throw new Error('TAB_LOCK_UNSUPPORTED') }
  assert.equal(await f.recorder.finishForRoomEnd(), false)
  assert.deepEqual(f.calls, [])
  await f.recorder.dispose()
})

test('lost segment response retries unchanged payload and does not confuse network outage with a gap', async () => {
  const f = fixture()
  await f.recorder.init(); await f.recorder.start()
  const requests = []
  f.api.send = async (id, item) => { requests.push(structuredClone(item)); if (requests.length === 1) throw new TypeError('network'); return {} }
  f.emit({ finals: [segment], interim: '' })
  await tick(); await f.recorder.draining
  assert.equal(f.recorder.data.gap, null)
  assert.equal(f.active(), true)
  await f.recorder.retry()
  assert.deepEqual(requests[0], requests[1])
  assert.equal(f.recorder.getSnapshot().pending, 0)
  await f.recorder.dispose()
})

test('queued gaps keep their server-based times across manual resume and delayed replay', async () => {
  const f = fixture()
  f.clock.elapsed = 100
  await f.recorder.init(); await f.recorder.start()
  f.recorder.data.retryPaused = true
  f.clock.elapsed += 2000
  await f.end('not-allowed')
  f.clock.serverTime = '2026-11-18T01:05:00.000Z'
  await f.recorder.start()
  f.clock.elapsed += 1300
  await f.end('not-allowed')
  f.clock.elapsed += 60000
  await f.recorder.retry()
  const items = f.calls.filter(x => x[0] === 'send').map(x => x[1])
  assert.deepEqual(items.map(x => x.kind), ['gapOpen', 'gapClose', 'gapOpen'])
  assert.equal(items[1].gapId, 9)
  assert.equal(items[0].body.startedAt, '2026-11-18T01:00:12.123Z')
  assert.equal(items[1].body.endedAt, '2026-11-18T01:05:00.000Z')
  assert.equal(items[2].body.startedAt, '2026-11-18T01:05:01.300Z')
  await f.recorder.dispose()
})

test('final result produced by stop is saved before server session stop', async () => {
  const f = fixture()
  await f.recorder.init(); await f.recorder.start()
  f.recorder.speech.stop = async () => f.emit({ finals: [segment], interim: '' })
  await f.recorder.stop()
  const order = f.calls.filter(x => ['send', 'stop'].includes(x[0])).map(x => x[0])
  assert.deepEqual(order, ['send', 'stop'])
  assert.equal(f.recorder.getSnapshot().phase, 'ended')
  await f.recorder.dispose()
})

test('navigation waits for trailing finals and accepts server acknowledgements while the disk is stalled', async () => {
  const f = fixture()
  await f.recorder.init(); await f.recorder.start()
  const disk = gate()
  const microphone = gate()
  const server = gate()
  f.store.save = () => disk.promise
  const send = f.api.send
  f.api.send = async (id, item) => {
    if (item.kind === 'segment') await server.promise
    return send(id, item)
  }
  f.recorder.speech.stop = async () => {
    await microphone.promise
    f.emit({ finals: [segment, { ...segment, transcript: '마지막 발언' }], interim: '' })
  }

  const prepare = f.recorder.prepareForNavigation()
  assert.equal(f.recorder.getSnapshot().navigationSafe, false)
  assert.equal(f.recorder.getSnapshot().awaitingSpeechEnd, true)
  microphone.release()
  await prepare
  assert.equal(f.recorder.getSnapshot().awaitingSpeechEnd, false)
  assert.equal(f.recorder.getSnapshot().hasUnsecuredSegments, true)
  assert.equal(f.recorder.getSnapshot().navigationSafe, false)
  server.release()
  await tick()
  assert.deepEqual(f.calls.filter(([kind, item]) => kind === 'send' && item.kind === 'segment')
    .map(([, item]) => item.body.transcript), ['트랜잭션', '마지막 발언'])
  assert.equal(f.recorder.getSnapshot().navigationSafe, true)
  assert.ok(f.calls.some(([kind, item]) => kind === 'send' && item.body.reason === 'MANUAL_STOP'))
  assert.equal(f.calls.some(([kind]) => kind === 'stop'), false)
  disk.release()
  await f.recorder.dispose()
})

test('a browser checkpoint must contain each final, including one received after the previous checkpoint began', async () => {
  const f = fixture()
  await f.recorder.init(); await f.recorder.start()
  const first = gate()
  const last = gate()
  const server = gate()
  const save = f.store.save
  const send = f.api.send
  f.store.save = async value => {
    const hasLast = value.queue.some(item => item.body.transcript === '마지막 발언')
    await (hasLast ? last : first).promise
    return save(value)
  }
  f.api.send = async (...args) => { await server.promise; return send(...args) }
  f.emit({ finals: [segment], interim: '' })
  f.recorder.speech.stop = async () => f.emit({ finals: [{ ...segment, transcript: '마지막 발언' }], interim: '' })
  await f.recorder.prepareForNavigation()
  first.release()
  await tick()
  assert.equal(f.recorder.getSnapshot().navigationSafe, false)
  assert.equal(f.recorder.getSnapshot().unsecuredSegmentCount, 1)
  last.release()
  await tick()
  assert.equal(f.recorder.getSnapshot().navigationSafe, true)
  assert.equal(f.recorder.getSnapshot().unsecuredSegmentCount, 0)
  const localCopy = structuredClone(f.snapshot())
  const durableKeys = localCopy.queue.filter(item => item.kind === 'segment').map(item => item.body.segmentKey)
  assert.equal(new Set(durableKeys).size, 2)
  server.release()
  await f.recorder.dispose()
  const restored = fixture(localCopy)
  await restored.recorder.init()
  await restored.recorder.draining
  assert.deepEqual(restored.calls.filter(([kind, item]) => kind === 'send' && item.kind === 'segment')
    .map(([, item]) => item.body.segmentKey), durableKeys)
  assert.equal(restored.active(), false)
  await restored.recorder.dispose()
})

test('both saves can fail; a later disk retry secures archived finals and keeps recognition paused', async () => {
  const f = fixture()
  await f.recorder.init(); await f.recorder.start()
  const save = f.store.save
  f.store.save = async () => { throw new Error('quota') }
  f.api.send = async () => { throw new ApiError(409, 'ROOM_ENDED') }
  f.emit({ finals: [segment], interim: '' })
  await tick()
  await f.recorder.prepareForNavigation()
  await tick()
  assert.equal(f.recorder.getSnapshot().pending, 0)
  assert.equal(f.recorder.getSnapshot().hasUnsecuredSegments, true)
  assert.equal(f.recorder.getSnapshot().navigationSafe, false)
  f.store.save = save
  f.recorder.retryNavigationSave()
  await tick()
  assert.ok(f.snapshot().archived.some(item => item.body.transcript === segment.transcript))
  assert.equal(f.recorder.getSnapshot().navigationSafe, true)
  await f.recorder.start(true)
  assert.equal(f.active(), false)
  await f.recorder.dispose()
})

test('offline finals can be exported from memory and a local retry succeeds while still offline', async () => {
  const f = fixture()
  await f.recorder.init(); await f.recorder.start()
  const save = f.store.save
  f.store.save = async () => { throw new Error('quota') }
  f.api.me = async () => { throw new TypeError('offline') }
  f.emit({ finals: [segment, { ...segment, transcript: '두 번째 발언' }], interim: '임시 발언' })
  await tick()
  assert.equal(f.recorder.getSnapshot().unsecuredSegmentCount, 2)
  const exported = JSON.parse(await f.recorder.exportRecords())
  assert.equal(exported.roomId, 10)
  assert.deepEqual(exported.records.filter(item => item.kind === 'segment').map(item => item.body.transcript),
    [segment.transcript, '두 번째 발언'])
  assert.equal(f.recorder.getSnapshot().unsecuredSegmentCount, 2)
  f.store.save = save
  await f.recorder.retry()
  assert.equal(f.recorder.getSnapshot().unsecuredSegmentCount, 0)
  assert.equal(f.recorder.getSnapshot().pending, 2)
  assert.equal(f.active(), false)
  await f.recorder.dispose()
})

test('a room change saves the trailing final only to the original recorder and cancels a pending export', async () => {
  const f = fixture()
  await f.recorder.init(); await f.recorder.start()
  const auth = gate()
  f.api.me = async () => { await auth.promise; return { userId: 1 } }
  const download = f.recorder.exportRecords()
  f.recorder.speech.stop = async () => f.emit({ finals: [segment], interim: '' })
  await f.recorder.dispose()
  auth.release()
  await assert.rejects(download)
  assert.deepEqual(f.snapshot().queue.filter(item => item.kind === 'segment').map(item => item.body.transcript),
    [segment.transcript])
  assert.equal(f.calls.filter(([kind]) => kind === 'send').length, 0)
})

test('a delayed identity response cannot expose memory-only records after an account switch', async () => {
  const f = fixture()
  await f.recorder.init(); await f.recorder.start()
  f.failStorage()
  f.recorder.data.retryPaused = true
  f.emit({ finals: [segment], interim: '' })
  await tick()
  const auth = gate()
  f.api.me = async () => { await auth.promise; return { userId: 1 } }
  const downloading = f.recorder.exportRecords()
  f.api.me = async () => ({ userId: 2 })
  await f.recorder.retry()
  auth.release()
  await assert.rejects(downloading)
  assert.equal(f.recorder.getSnapshot().unsecuredSegmentCount, 0)
  assert.equal(f.recorder.getSnapshot().visible, false)
  assert.equal(f.calls.filter(([kind]) => kind === 'send').length, 0)
  await f.recorder.dispose()
})

test('staying after a navigation pause leaves the same session idle until manual resume closes its gap', async () => {
  const f = fixture()
  await f.recorder.init(); await f.recorder.start()
  const key = f.recorder.data.startKey
  await f.recorder.prepareForNavigation()
  await f.recorder.draining
  await f.recorder.start(true)
  assert.equal(f.active(), false)
  assert.equal(f.recorder.getSnapshot().navigationSafe, true)
  await f.recorder.start()
  await f.recorder.draining
  assert.equal(f.active(), true)
  assert.equal(f.recorder.getSnapshot().navigationSafe, false)
  assert.equal(f.recorder.data.startKey, key)
  assert.deepEqual(f.calls.filter(([kind]) => kind === 'send').map(([, item]) => item.kind), ['gapOpen', 'gapClose'])
  await f.recorder.dispose()
})

test('initial microphone failure closes ERROR; another attempt uses a new session key', async () => {
  const f = fixture()
  await f.recorder.init()
  f.recorder.speech.start = async () => { throw new Error('not-allowed') }
  await f.recorder.start(); await f.recorder.draining
  const oldKey = f.recorder.data.startKey
  assert.ok(f.calls.some(x => x[0] === 'stop' && x[1] === 'ERROR'))
  f.recorder.speech.start = async () => {}
  await f.recorder.start()
  assert.notEqual(f.recorder.data.startKey, oldKey)
  await f.recorder.dispose()
})

test('refresh restores pending payloads, never automatically starts the microphone', async () => {
  const f = fixture()
  await f.recorder.init(); await f.recorder.start()
  f.recorder.data.retryPaused = true
  f.emit({ finals: [segment], interim: '' })
  await tick()
  const saved = structuredClone(f.snapshot())
  await f.recorder.dispose()
  const restored = fixture(saved)
  await restored.recorder.init()
  assert.equal(restored.active(), false)
  assert.equal(restored.recorder.data.queue[0].body.segmentKey, saved.queue[0].body.segmentKey)
  assert.match(restored.recorder.getSnapshot().message, /정확한 중단 시각/)
  await restored.recorder.dispose()
})

test('authentication expiry stops recognition, keeps data and does not expose another account records', async () => {
  const f = fixture()
  await f.recorder.init(); await f.recorder.start()
  f.api.send = async () => { throw new ApiError(401, 'UNAUTHENTICATED') }
  f.emit({ finals: [segment], interim: '' })
  await tick(); await f.recorder.draining
  assert.equal(f.active(), false)
  assert.equal(f.recorder.getSnapshot().authOk, false)
  assert.ok(f.snapshot().queue.length)
  f.api.me = async () => ({ userId: 2 })
  await f.recorder.retry()
  assert.equal(f.recorder.getSnapshot().visible, false)
  assert.equal(f.recorder.getSnapshot().recent.length, 0)
  await assert.rejects(f.recorder.exportRecords())
  await f.recorder.dispose()
})

test('automatic retries stop after five; new utterances do not restart the budget', async () => {
  const f = fixture()
  await f.recorder.init(); await f.recorder.start()
  let requests = 0
  f.api.send = async () => { requests++; throw new TypeError('network') }
  f.emit({ finals: [segment], interim: '' })
  await tick(); await f.recorder.draining
  for (const expected of [1000, 2000, 4000, 8000, 16000]) {
    const id = f.recorder.retryTimer
    assert.equal(f.timers.get(id).delay, expected)
    f.timers.get(id).fn()
    await tick(); await f.recorder.draining
  }
  assert.equal(requests, 6)
  assert.equal(f.recorder.data.retryPaused, true)
  f.emit({ finals: [segment], interim: '' })
  await tick()
  assert.equal(requests, 6)
  await f.recorder.dispose()
})

test('discard-and-stop retains records for export and never resends them', async () => {
  const f = fixture()
  await f.recorder.init(); await f.recorder.start()
  f.recorder.data.retryPaused = true
  f.emit({ finals: [segment], interim: '' })
  await tick()
  await f.recorder.stop(true)
  assert.equal(f.recorder.data.queue.length, 0)
  assert.equal(f.recorder.data.archived.length, 1)
  assert.equal(f.calls.filter(x => x[0] === 'send').length, 0)
  assert.match(await f.recorder.exportRecords(), /트랜잭션/)
  f.api.me = async () => { throw new TypeError('network') }
  assert.match(await f.recorder.exportRecords(), /트랜잭션/)
  await f.recorder.dispose()
})

test('permanent rejection archives instead of retrying forever', async () => {
  const f = fixture()
  await f.recorder.init(); await f.recorder.start()
  f.api.send = async () => { throw new ApiError(409, 'ROOM_ENDED') }
  f.emit({ finals: [segment], interim: '' })
  await tick(); await f.recorder.draining
  assert.ok(f.recorder.data.archived.some(x => x.body.transcript === '트랜잭션'))
  assert.equal(f.active(), false)
  assert.equal(f.recorder.retryTimer, undefined)
  await f.recorder.dispose()
})

test('storage failure stops recognition but keeps unsaved data in memory', async () => {
  const f = fixture()
  await f.recorder.init(); await f.recorder.start()
  f.recorder.data.retryPaused = true
  f.failStorage()
  f.emit({ finals: [segment], interim: '' })
  await tick()
  assert.equal(f.active(), false)
  assert.equal(f.recorder.getSnapshot().storageOk, false)
  assert.equal(f.recorder.getSnapshot().pending, 1)
  await f.recorder.dispose()
})

test('unavailable tab lock never opens microphone or overwrites stored data', async () => {
  const f = fixture({ protected: true })
  f.recorder.acquire = async () => null
  await f.recorder.init()
  await f.recorder.start()
  await f.recorder.dispose()
  assert.equal(f.active(), false)
  assert.deepEqual(f.snapshot(), { protected: true })
})

test('failed auth during initialization does not erase a previously stored snapshot', async () => {
  const f = fixture({ protected: true })
  f.api.me = async () => { throw new ApiError(401) }
  await f.recorder.init(); await f.recorder.dispose()
  assert.deepEqual(f.snapshot(), { protected: true })
})

test('recognizer stop accepts trailing final before resolving end', async () => {
  let engine
  class FakeRecognition {
    constructor() { engine = this }
    start() { queueMicrotask(() => this.onstart()) }
    stop() {
      this.onresult({ results: [Object.assign([{ transcript: '마지막 말' }], { isFinal: true })] })
      this.onend()
    }
  }
  const finals = []
  const speech = createSpeech({ Recognition: FakeRecognition, onResult: result => finals.push(...result.finals),
    onEnd: () => assert.fail('intentional stop must not restart'), now: () => instant })
  await speech.start()
  assert.equal(engine.lang, 'ko-KR')
  await speech.stop()
  assert.equal(finals[0].transcript, '마지막 말')
})

test('disposing speech during a pending pause settles the stop without scheduling a restart', async () => {
  class FakeRecognition {
    start() { queueMicrotask(() => this.onstart()) }
    stop() {}
    abort() { this.onend() }
  }
  const speech = createSpeech({ Recognition: FakeRecognition,
    onEnd: () => assert.fail('disposal must not restart recognition') })
  await speech.start()
  const stopped = speech.stop()
  speech.abort()
  await stopped
})

test('a different active session is never adopted or stopped after a rejected start and refresh', async () => {
  const f = fixture()
  await f.recorder.init()
  f.api.start = async () => ({ sttSessionId: 99, status: 'RECORDING', canRecord: false, serverTime: instant })
  await f.recorder.start()
  assert.equal(f.recorder.data.session, null)
  assert.equal(f.active(), false)
  await f.recorder.dispose()
  const restored = fixture(f.snapshot())
  restored.api.start = f.api.start
  await restored.recorder.init()
  await restored.recorder.stop()
  assert.equal(restored.calls.filter(x => x[0] === 'stop').length, 0)
  await restored.recorder.dispose()
})

test('unexpected end retries once; immediately ending again requires a manual restart', async () => {
  const f = fixture()
  await f.recorder.init(); await f.recorder.start()
  await f.end('network')
  const restart = f.timers.get(f.recorder.restartTimer)
  assert.equal(restart.delay, 500)
  f.clock.serverTime = '2026-11-18T01:00:22.000Z'
  await restart.fn()
  await f.recorder.draining
  const recovered = f.calls.find(([kind, item]) => kind === 'send' && item.kind === 'gapClose')
  assert.equal(recovered[1].body.endedAt, f.clock.serverTime)
  await f.end('network')
  assert.match(f.recorder.getSnapshot().message, /녹음 재개/)
  assert.equal(f.recorder.phase, 'paused')
  await f.recorder.dispose()
})

test('known server-side end stops microphone even when the speaker is silent', async () => {
  const f = fixture()
  await f.recorder.init(); await f.recorder.start()
  f.api.start = async () => ({ sttSessionId: 7, status: 'RECORDING', canRecord: false, serverTime: instant })
  await f.timers.get(f.recorder.pollTimer).fn()
  await f.recorder.draining
  assert.equal(f.active(), false)
  assert.ok(f.calls.some(x => x[0] === 'stop'))
  await f.recorder.dispose()
})

test('refresh does not reuse a server gap that already has a queued close', async () => {
  const f = fixture()
  await f.recorder.init(); await f.recorder.start()
  f.recorder.data.retryPaused = true
  await f.end('not-allowed')
  await f.recorder.start()
  await f.recorder.persist()
  const saved = structuredClone(f.snapshot())
  await f.recorder.dispose()
  const restored = fixture(saved)
  restored.api.records = async () => ({ gaps: [{ gapId: 9, sessionId: 7, startedAt: instant, endedAt: null }] })
  await restored.recorder.init()
  assert.equal(restored.recorder.data.gap.gapId, null)
  assert.equal(restored.recorder.data.queue.filter(x => x.kind === 'gapOpen').length, 2)
  await restored.recorder.dispose()
})
