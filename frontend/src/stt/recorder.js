import { ApiError, isTemporary } from './api.js'

const RETRIES = [1000, 2000, 4000, 8000, 16000]
const fresh = () => ({ version: 1, startKey: null, session: null, ownsSession: false, queue: [], archived: [],
  gap: null, stopReason: null, retryPaused: false, retryCount: 0, wasListening: false })

export class Recorder {
  constructor({ userId, roomId, api, store, acquire, speechFactory,
    uuid = () => crypto.randomUUID(), now = () => new Date().toISOString(),
    schedule = (fn, delay) => setTimeout(fn, delay), cancel = id => clearTimeout(id) }) {
    Object.assign(this, { userId, roomId, api, store, acquire, uuid, now, schedule, cancel })
    this.data = fresh()
    this.listeners = new Set()
    this.saving = Promise.resolve()
    this.phase = 'loading'
    this.message = ''
    this.interim = ''
    this.recent = []
    // Loaded records already have a durable copy. Only newly emitted finals enter this set.
    this.unsecuredSegments = new Set()
    this.storageOk = true
    this.authOk = true
    this.visible = true
    this.eligible = false
    this.speech = speechFactory({ onResult: value => this.result(value), onEnd: error => this.interrupted(error), now })
    this.publish()
  }

  subscribe = listener => { this.listeners.add(listener); return () => this.listeners.delete(listener) }
  getSnapshot = () => this.view
  publish() {
    this.view = {
      phase: this.phase, message: this.message, interim: this.visible ? this.interim : '',
      recent: this.visible ? [...this.recent] : [],
      pending: this.visible ? this.data.queue.filter(x => x.kind === 'segment').length : 0,
      pendingGaps: this.visible ? this.data.queue.filter(x => x.kind !== 'segment').length : 0,
      archived: this.visible ? this.data.archived.length : 0,
      retryPaused: this.data.retryPaused, retrying: !!this.retryTimer,
      sending: !!this.draining, busy: !!this.busy,
      storageOk: this.storageOk, authOk: this.authOk, visible: this.visible,
      hasSession: !!this.data.session, stopPending: !!this.data.stopReason,
      readyToCloseRoom: this.readyToCloseRoom(),
      hasUnsecuredSegments: this.unsecuredSegments.size > 0,
      awaitingSpeechEnd: !!this.pausing,
      navigationSafe: this.navigationPause === 'paused' && this.unsecuredSegments.size === 0,
      canStart: !!this.release && this.visible && !this.busy && !this.disposed
        && this.navigationPause !== 'pausing'
        && !['listening', 'starting', 'stopping', 'loading', 'blocked'].includes(this.phase),
    }
    for (const listener of this.listeners) listener()
  }

  async persist() {
    const snapshot = structuredClone(this.data)
    const task = this.saving.catch(() => {}).then(() => this.store.save(snapshot))
    this.saving = task
    try {
      await task
      if (this.unsecuredSegments.size) {
        // Another final may have arrived while this particular snapshot was being written.
        for (const item of [...snapshot.queue, ...snapshot.archived]) {
          if (item.kind === 'segment') this.unsecuredSegments.delete(item.body.segmentKey)
        }
        this.publish()
      }
      return true
    } catch {
      if (this.storageOk) {
        this.storageOk = false
        this.message = '브라우저에 기록을 보관할 수 없어 녹음을 중단했습니다. 화면을 닫으면 일부 기록을 잃을 수 있습니다.'
        this.pause('ERROR')
      }
      this.publish()
      return false
    }
  }

  async prepareForNavigation() {
    this.navigationPause = 'pausing'
    const stopped = this.pause('MANUAL_STOP')
    this.publish()
    // The engine can emit further finals after stop() and before its end event.
    await stopped
    if (this.disposed) return
    this.navigationPause = 'paused'
    this.retryNavigationSave()
    this.publish()
  }

  retryNavigationSave() {
    if (!this.loaded || !this.release || this.disposed) return
    this.data.retryCount = 0
    this.data.retryPaused = false
    this.cancel(this.retryTimer)
    this.retryTimer = null
    // Either destination is sufficient. An unavailable server must not delay the disk write.
    void this.persist().then(ok => {
      if (ok) this.storageOk = true
      this.publish()
    })
    if (this.authOk) this.drain()
    else this.verifyUser().then(() => this.drain()).catch(error => this.fail(error))
  }

  readyToCloseRoom() {
    const { session, queue, stopReason, startKey, archived } = this.data
    return this.loaded && this.visible && this.authOk && !this.disposed && !this.busy
      && this.phase !== 'blocked' && !queue.length && !stopReason
      && (!startKey || !!session) && session?.status !== 'RECORDING'
      && !archived.some(item => item.sessionId === session?.sttSessionId)
  }

  async finishForRoomEnd() {
    await this.init()
    if (this.readyToCloseRoom()) return true
    if (!this.disposed && this.lockUnsupported) {
      // Recording cannot start here. Still check for work left by an earlier visit.
      const saved = await this.store.load()
      return !saved || (saved.version === 1 && Array.isArray(saved.queue) && !saved.queue.length
        && Array.isArray(saved.archived) && !saved.archived.length
        && !saved.stopReason && !saved.gap && (!saved.startKey || !!saved.session)
        && saved.session?.status !== 'RECORDING')
    }
    if (this.disposed || !this.loaded || !this.release || this.busy || this.phase === 'blocked') return false
    // A lost start response may have left a server session even before the microphone opened.
    if (!this.data.session && this.data.startKey) {
      await this.verifyUser()
      const session = await this.api.start(this.data.startKey)
      if (session.status === 'RECORDING' && !session.canRecord) return false
      this.data.session = session
      this.data.ownsSession = session.canRecord
      await this.persist()
    }
    if (this.data.stopReason) await this.retry()
    else if (this.data.session?.status === 'RECORDING' && this.data.ownsSession) await this.stop()
    else if (this.data.queue.length) await this.retry()
    return this.readyToCloseRoom()
  }

  async verifyUser() {
    const me = await this.api.me()
    if (String(me.userId) !== String(this.userId)) {
      this.visible = false
      this.publish()
      throw new ApiError(401, 'ACCOUNT_CHANGED')
    }
    this.authOk = true
    this.visible = true
  }

  init() {
    if (!this.initializing) this.initializing = this.initialize()
    return this.initializing
  }

  async initialize() {
    if (this.disposed) return
    try {
      this.release = await this.acquire()
      if (this.disposed) { this.release?.(); return }
      if (!this.release) {
        this.phase = 'blocked'
        this.message = '다른 탭에서 녹음 화면을 사용 중입니다. 해당 탭을 닫고 다시 연결해 주세요.'
        return
      }
      await this.verifyUser()
      const records = await this.api.records() // Server ownership check before showing local content.
      const saved = await this.store.load()
      if (saved && (saved.version !== 1 || !Array.isArray(saved.queue) || !Array.isArray(saved.archived))) {
        throw new Error('INVALID_LOCAL_RECORD')
      }
      this.data = saved || fresh()
      this.loaded = true
      if (this.disposed) return
      this.phase = 'idle'
      if (this.data.startKey && (!this.data.session || this.data.session.status === 'RECORDING')) {
        const session = await this.api.start(this.data.startKey)
        if (!session.canRecord && session.status === 'RECORDING' && !this.data.ownsSession) {
          this.phase = 'paused'
          this.message = '다른 녹음 세션이 진행 중입니다. 해당 세션을 종료한 후 다시 시작해 주세요.'
          await this.persist()
          return
        }
        this.data.session = session
        if (session.canRecord) this.data.ownsSession = true
        this.eligible = session.canRecord
        this.phase = session.canRecord ? 'paused' : 'ended'
        if (session.canRecord && !this.data.stopReason) {
          const existing = records.gaps.find(gap => gap.sessionId === session.sttSessionId && !gap.endedAt)
          const awaitingClose = this.data.queue.some(item => item.kind === 'gapClose')
          if (!this.data.gap && existing && !awaitingClose) {
            this.data.gap = { key: this.uuid(), gapId: existing.gapId, startedAt: existing.startedAt }
          }
          if (!this.data.gap) {
            this.openGap('ERROR')
            this.message = '이전 녹음의 정확한 중단 시각을 확인할 수 없습니다. 녹음 재개를 눌러 주세요.'
          } else this.message = '음성 인식이 중단되어 있습니다. 녹음 재개를 눌러 주세요.'
        } else if (!session.canRecord && session.status === 'RECORDING') {
          this.data.stopReason = 'MANUAL_STOP'
          this.message = '강연이 종료되어 녹음을 재개할 수 없습니다.'
        }
        this.data.wasListening = false
      }
      await this.persist()
      this.drain()
      if (!this.disposed) this.poll()
    } catch (error) {
      this.phase = 'blocked'
      this.lockUnsupported = error.message === 'TAB_LOCK_UNSUPPORTED'
      this.message = this.lockUnsupported
        ? '이 브라우저에서는 안전한 탭 간 녹음 제어를 지원하지 않습니다. Chrome의 HTTPS 화면에서 이용해 주세요.'
        : '녹음 화면을 불러오지 못했습니다. 로그인·강연방 접근 권한과 브라우저 저장소를 확인해 주세요.'
      if (error.status === 401) this.authOk = false
    } finally { this.publish() }
  }

  openGap(reason) {
    if (this.data.gap || !this.data.session || !this.eligible || this.data.stopReason) return
    const key = this.uuid()
    const startedAt = this.now()
    this.data.gap = { key, startedAt, gapId: null }
    this.data.queue.push({ id: this.uuid(), kind: 'gapOpen', gapKey: key,
      body: { startKey: this.data.startKey, eventKey: key, reason, startedAt } })
  }

  closeGap() {
    const gap = this.data.gap
    if (!gap) return
    this.data.queue.push({ id: this.uuid(), kind: 'gapClose', gapKey: gap.key, gapId: gap.gapId,
      body: { startKey: this.data.startKey, endedAt: this.now() } })
    this.data.gap = null
  }

  result({ finals, interim }) {
    if (this.disposed || !this.data.session) return
    this.interim = interim
    for (const final of finals) {
      const segment = { ...final, startKey: this.data.startKey, segmentKey: this.uuid() }
      this.unsecuredSegments.add(segment.segmentKey)
      this.data.queue.push({ id: this.uuid(), kind: 'segment', body: segment })
      this.recent.push(segment)
      if (this.recent.length > 30) this.recent.shift()
    }
    this.publish()
    if (finals.length) this.persist().then(() => this.drain())
  }

  pause(reason = 'ERROR') {
    this.cancel(this.restartTimer)
    this.cancel(this.stableTimer)
    if (this.phase === 'listening' || this.phase === 'starting') {
      this.openGap(reason)
      this.data.wasListening = false
      this.phase = 'paused'
      return this.stopSpeech()
    }
    return this.pausing || Promise.resolve()
  }

  stopSpeech() {
    if (!this.pausing) {
      this.pausing = this.speech.stop().finally(() => {
        this.interim = ''
        this.pausing = null
        this.publish()
      })
      this.publish()
    }
    return this.pausing
  }

  async interrupted(error) {
    if (this.disposed || this.phase !== 'listening') return
    this.cancel(this.stableTimer)
    this.openGap(error === 'not-allowed' ? 'PERMISSION_DENIED' : 'ERROR')
    this.phase = 'paused'
    this.data.wasListening = false
    this.interim = ''
    this.message = '음성 인식이 중단되었습니다.'
    await this.persist()
    this.drain()
    const denied = ['not-allowed', 'service-not-allowed', 'audio-capture', 'language-not-supported'].includes(error)
    if (denied) this.message = '마이크 권한·장치·인식 언어를 확인한 후 녹음 재개를 눌러 주세요.'
    else if (!this.navigationPause && !this.autoUsed && this.storageOk && this.authOk) {
      this.autoUsed = true
      this.message = '음성 인식을 한 번 자동 재시작합니다.'
      this.restartTimer = this.schedule(() => this.start(true), 500)
    } else this.message = '자동 재시작을 마쳤습니다. 녹음 재개를 눌러 주세요.'
    this.publish()
  }

  async start(automatic = false) {
    if (this.busy || this.disposed || !this.loaded || !this.release || this.phase === 'listening') return
    if (this.navigationPause === 'pausing' || (automatic && this.navigationPause)) return
    this.navigationPause = null
    this.busy = true
    this.phase = 'starting'
    this.message = '녹음 시작 가능 여부를 확인하고 있습니다.'
    this.publish()
    let firstStart = false
    try {
      await this.verifyUser()
      if (this.disposed) return
      await this.pausing
      if (!await this.persist()) throw new Error('STORAGE')
      if (this.disposed || this.navigationPause) return
      this.storageOk = true
      if (this.data.stopReason) {
        this.message = '이전 세션의 종료 처리를 먼저 완료해 주세요.'
        this.phase = 'stopping'
        return
      }
      if (!this.data.startKey || this.data.session?.status !== 'RECORDING' && this.data.session) {
        if (this.data.queue.length) throw new Error('PENDING_RECORDS')
        this.data = { ...fresh(), archived: this.data.archived, startKey: this.uuid() }
        this.recent = []
      }
      // Save the key before POST: a lost response must never generate another key.
      if (!this.data.startKey) this.data.startKey = this.uuid()
      if (!await this.persist()) throw new Error('STORAGE')
      if (this.disposed || this.navigationPause) return
      const session = await this.api.start(this.data.startKey)
      if (this.disposed) return
      if (!session.canRecord && session.status === 'RECORDING' && !this.data.ownsSession) {
        this.phase = 'paused'
        this.message = '다른 녹음 세션이 진행 중입니다. 해당 세션을 종료한 후 다시 시작해 주세요.'
        return
      }
      this.data.session = session
      if (session.canRecord) this.data.ownsSession = true
      this.eligible = session.canRecord
      if (!session.canRecord) {
        this.phase = 'ended'
        this.message = '강연·세션이 종료되었거나 다른 녹음 세션이 진행 중입니다.'
        await this.persist()
        return
      }
      firstStart = !this.data.everStarted
      if (!await this.persist()) throw new Error('STORAGE')
      if (this.navigationPause || this.disposed) return
      await this.speech.start()
      if (this.disposed) { this.speech.abort(); return }
      if (this.navigationPause) return
      this.closeGap()
      this.data.everStarted = true
      this.data.wasListening = true
      this.phase = 'listening'
      this.message = ''
      if (!automatic) this.autoUsed = false
      // Do not loop forever when an engine repeatedly starts and immediately ends.
      this.stableTimer = this.schedule(() => { this.autoUsed = false }, 30000)
      await this.persist()
      this.drain()
    } catch (error) {
      this.phase = 'paused'
      this.message = '녹음을 시작하지 못했습니다. 연결·마이크 권한을 확인한 후 다시 시도해 주세요.'
      if (firstStart && !this.navigationPause && this.data.session && this.eligible && !error.status && error.message !== 'STORAGE') {
        this.data.stopReason = 'ERROR'
        this.phase = 'stopping'
        this.message = '처음 마이크를 켜지 못해 세션을 종료합니다. 권한과 장치를 확인해 주세요.'
        await this.persist()
        this.drain()
      } else if (error.status === 401) await this.fail(error)
      else if (error.message === 'STORAGE') this.message = '브라우저 저장소를 사용할 수 없어 녹음을 시작하지 않았습니다.'
    } finally { this.busy = false; this.publish() }
  }

  async stop(discard = false) {
    if (this.busy || !this.data.session || this.disposed) return
    this.busy = true
    this.cancel(this.restartTimer)
    this.cancel(this.stableTimer)
    this.phase = 'stopping'
    this.message = '남은 기록 저장 중입니다.'
    this.publish()
    await this.stopSpeech() // result events received before end are enqueued first.
    this.data.wasListening = false
    this.data.stopReason ||= 'MANUAL_STOP'
    if (discard) {
      // Wait for an in-flight write before moving remaining requests out of the queue.
      if (this.draining) await this.draining
      this.archiveQueue('강연자가 서버 저장 없이 종료를 선택했습니다.')
    }
    this.data.retryPaused = false
    this.data.retryCount = 0
    this.cancel(this.retryTimer)
    this.retryTimer = null
    await this.persist()
    this.busy = false
    this.publish()
    await this.drain()
  }

  archiveQueue(reason) {
    this.data.archived.push(...this.data.queue.map(item => ({ ...item,
      sessionId: this.data.session?.sttSessionId, reason })))
    this.data.queue = []
  }

  async retry() {
    if (this.disposed || !this.loaded || !this.release || this.busy || this.phase === 'blocked') return
    try {
      await this.verifyUser()
      if (await this.persist()) this.storageOk = true
      this.data.retryCount = 0
      this.data.retryPaused = false
      this.cancel(this.retryTimer)
      this.retryTimer = null
      await this.persist()
      this.publish()
      await this.drain()
    } catch (error) { await this.fail(error) }
  }

  drain() {
    if (this.draining) return this.draining
    if (this.disposed || !this.authOk || !this.release || this.data.retryPaused || this.retryTimer) return Promise.resolve()
    this.draining = this.flush().finally(() => {
      this.draining = null
      this.publish()
      if (this.data.session && (this.data.queue.length || this.data.stopReason)
          && this.authOk && !this.data.retryPaused && !this.retryTimer && !this.disposed) {
        queueMicrotask(() => this.drain())
      }
    })
    this.publish()
    return this.draining
  }

  async flush() {
    if (!this.data.session || (!this.data.queue.length && !this.data.stopReason)) return
    try {
      await this.verifyUser()
      while (this.data.queue.length && !this.disposed) {
        const item = this.data.queue[0]
        if (item.kind === 'gapClose' && !item.gapId) throw new ApiError(409, 'GAP_DEPENDENCY')
        try {
          const response = await this.api.send(this.data.session.sttSessionId, item)
          if (item.kind === 'segment') this.unsecuredSegments.delete(item.body.segmentKey)
          if (item.kind === 'gapOpen') {
            if (this.data.gap?.key === item.gapKey) this.data.gap.gapId = response.gapId
            for (const pending of this.data.queue) {
              if (pending.gapKey === item.gapKey && pending.kind === 'gapClose') pending.gapId = response.gapId
            }
          }
          this.data.queue = this.data.queue.filter(x => x.id !== item.id)
          this.data.retryCount = 0
          // Writes are serialized by persist(). A slow disk must not stall the upload queue.
          void this.persist()
          this.publish()
        } catch (error) {
          if (isTemporary(error) || error.status === 401) throw error
          await this.pause()
          this.archiveQueue('서버가 저장을 거절했습니다. 강연 종료·권한·요청 충돌을 확인해 주세요.')
          this.data.stopReason ||= this.data.session.status === 'RECORDING' ? 'ERROR' : null
          this.message = '일부 기록을 서버에 저장하지 못했습니다. 남은 기록을 다운로드할 수 있습니다.'
          await this.persist()
          break
        }
      }
      if (this.data.stopReason && !this.disposed) {
        const reason = this.data.stopReason
        const session = await this.api.stop(this.data.session.sttSessionId, this.data.stopReason)
        this.data.session = session
        this.data.stopReason = null
        this.data.gap = null
        this.data.wasListening = false
        this.eligible = false
        this.phase = 'ended'
        this.message = reason === 'ERROR'
          ? '녹음 오류로 세션이 종료되었습니다. 마이크 권한·장치와 저장 안내를 확인한 후 다시 시작해 주세요.'
          : '녹음이 종료되었습니다.'
        await this.persist()
      }
    } catch (error) { void this.fail(error) }
  }

  async fail(error) {
    if (this.disposed) return
    if (error.status === 401) {
      this.authOk = false
      this.pause()
      this.data.retryPaused = true
      this.message = this.visible ? '로그인이 만료되어 녹음을 중단했습니다. 같은 계정으로 로그인해 주세요.'
        : '다른 계정으로 로그인되어 이전 계정의 기록을 숨겼습니다.'
    } else if (isTemporary(error)) {
      const delay = RETRIES[this.data.retryCount]
      if (delay !== undefined) {
        this.data.retryCount++
        this.retryTimer = this.schedule(() => { this.retryTimer = null; this.drain() }, delay)
        this.message = '연결 문제로 기록 저장을 재시도하고 있습니다.'
      } else {
        this.data.retryPaused = true
        this.message = '미전송 기록이 남아 있습니다. 저장 재시도를 눌러 주세요.'
      }
    } else {
      this.pause()
      this.data.retryPaused = true
      this.message = '요청이 거절되었습니다. 강연 상태와 접근 권한을 확인해 주세요.'
    }
    await this.persist()
    this.publish()
  }

  poll() {
    this.pollTimer = this.schedule(async () => {
      try {
        if (!this.disposed && this.authOk && this.data.session?.status === 'RECORDING' && !this.busy) {
          await this.verifyUser()
          const session = await this.api.start(this.data.startKey)
          if (!session.canRecord) {
            this.eligible = false
            await this.pause()
            this.data.session = session
            this.phase = 'ended'
            this.message = '강연 또는 녹음 세션이 종료되었습니다.'
            if (session.status === 'RECORDING') this.data.stopReason ||= 'MANUAL_STOP'
            await this.persist()
            this.drain()
          }
        }
      } catch (error) {
        if (error.status === 401 || error.status === 403) await this.fail(error)
      } finally { if (!this.disposed) this.poll(); this.publish() }
    }, 15000)
  }

  async verifyLocalAccess() {
    if (!this.loaded || !this.visible) throw new ApiError(401, 'ACCOUNT_CHANGED')
    try { await this.verifyUser() } catch (error) {
      // Allow recovery downloads during an outage, using this screen's verified account.
      if (isTemporary(error) && this.authOk && this.visible) return
      await this.fail(error)
      throw error
    }
  }

  async exportRecords() {
    await this.verifyLocalAccess()
    return JSON.stringify({ roomId: this.roomId, exportedAt: this.now(),
      notice: '추정 시각입니다. 서버 보고서에 자동 반영되지 않습니다.',
      records: this.data.archived }, null, 2)
  }

  async deleteArchived() {
    await this.verifyLocalAccess()
    const previous = this.data.archived
    this.data.archived = []
    if (!await this.persist()) {
      this.data.archived = previous
      this.publish()
      throw new Error('STORAGE')
    }
    for (const item of previous) {
      if (item.kind === 'segment') this.unsecuredSegments.delete(item.body.segmentKey)
    }
    this.publish()
  }

  dispose() {
    if (!this.disposing) this.disposing = this.cleanup()
    return this.disposing
  }

  async cleanup() {
    this.disposed = true
    for (const timer of [this.retryTimer, this.restartTimer, this.pollTimer, this.stableTimer]) this.cancel(timer)
    if (this.data.wasListening) this.openGap('ERROR')
    this.data.wasListening = false
    this.speech.abort()
    await this.initializing
    if (this.release) {
      // Keep ownership until outstanding writes finish, preventing stale snapshots in another tab.
      await this.draining
      if (this.loaded) await this.persist()
      this.release()
      this.release = null
    }
  }
}
