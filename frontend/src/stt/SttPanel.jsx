import React, { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { sttApi } from './api.js'
import { createStore, acquireTab } from './storage.js'
import { createSpeech, speechConstructor } from './speech.js'
import { Recorder } from './recorder.js'
import './stt.css'

const labels = { loading: '연결 확인 중', idle: '시작 전', starting: '인식 준비 중',
  listening: '인식 중', paused: '인식 중단', stopping: '인식 종료 · 저장 중', ended: '인식 종료', blocked: '연결 불가' }

function RecordingView({ recorder, integrated, ending, reconnect }) {
  const state = useSyncExternalStore(recorder.subscribe, recorder.getSnapshot)
  const [actionError, setActionError] = useState('')
  const supported = !!speechConstructor() && !!navigator.locks && !!globalThis.isSecureContext
  async function download() {
    try {
      const text = await recorder.exportRecords()
      const url = URL.createObjectURL(new Blob([text], { type: 'application/json;charset=utf-8' }))
      const link = document.createElement('a')
      link.href = url
      link.download = `stt-room-${recorder.roomId}-unsaved.json`
      link.click()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
    } catch { setActionError('기록을 내려받지 못했습니다. 같은 계정으로 로그인되어 있는지 확인해 주세요.') }
  }
  async function remove() {
    if (!window.confirm('서버에 저장하지 못한 보관 기록을 삭제할까요? 다운로드하지 않았다면 복구할 수 없습니다.')) return
    try { await recorder.deleteArchived() } catch { setActionError('보관 기록을 삭제하지 못했습니다.') }
  }
  const unsaved = state.pending + state.pendingGaps
  const canRetry = state.retryPaused || state.retrying || state.stopPending || unsaved > 0 || !state.authOk
  const saving = state.phase === 'loading' || state.phase === 'blocked' ? '저장 상태 확인 필요'
    : state.retryPaused ? '저장 재시도 필요' : state.retrying ? '전송 재시도 중'
    : state.archived && !unsaved ? '미저장 보관 기록 있음'
    : state.sending ? '서버 저장 중' : unsaved ? '전송 대기 중' : '서버 저장 완료'
  const Transcript = integrated ? 'details' : 'div'
  return <section className="stt-panel" aria-label="강연 발언 기록">
    <header>{!integrated && <span className="stt-eyebrow">강연방 {recorder.roomId}</span>}<h2>발언 기록</h2></header>
    {!supported && <p role="alert">이 환경에서는 녹음을 시작할 수 없습니다. Web Speech API와 탭 제어를 지원하는 Chrome에서 HTTPS로 접속해 주세요. 로컬 개발은 localhost를 사용하세요.</p>}
    <div className="stt-status" aria-live="polite">
      <div><span>음성 인식</span><strong className={state.phase === 'listening' ? 'stt-live' : ''}>{labels[state.phase]}</strong></div>
      <div><span>기록 저장</span><strong>{state.visible ? saving : '계정 확인 필요'}</strong>
        {state.visible && <small>미전송 발언 {state.pending}건 · 누락 구간 요청 {state.pendingGaps}건</small>}</div>
    </div>
    {state.message && <p className="stt-notice" role="status">{state.message}</p>}
    {actionError && <p role="alert">{actionError}</p>}
    <div className="stt-actions">
      {(!integrated || state.phase !== 'listening') && <button disabled={ending || !supported || !state.canStart || state.stopPending} onClick={() => recorder.start()}>
        {state.hasSession && state.phase !== 'ended' ? '녹음 재개' : '녹음 시작'}</button>}
      <button className={integrated && state.phase === 'listening' ? '' : 'secondary'} disabled={ending || !state.hasSession || state.busy || !state.visible || ['ended', 'blocked'].includes(state.phase)}
        onClick={() => recorder.stop()}>{integrated ? '녹음 중지' : '녹음 종료'}</button>
      {!ending && (!integrated || canRetry) && <button className="secondary" disabled={state.busy || state.phase === 'blocked' || !state.visible}
        onClick={() => recorder.retry()}>저장 재시도</button>}
      {integrated && state.phase === 'blocked' && <button className="secondary" onClick={reconnect}>녹음 다시 연결</button>}
      {!integrated && state.stopPending && (unsaved > 0 || state.retryPaused) && state.visible && <button className="secondary"
        disabled={state.busy} onClick={() => {
          if (window.confirm(`서버 저장이 확인되지 않은 발언 ${state.pending}건과 누락 구간 요청 ${state.pendingGaps}건이 있습니다. 저장하지 않고 종료할까요? 남은 기록은 다운로드할 수 있습니다.`)) recorder.stop(true)
        }}>남은 기록을 서버에 저장하지 않고 종료</button>}
    </div>
    <Transcript className="stt-transcript">
      {integrated ? <summary>최근 발언 보기</summary> : <h3>최근 확정 전사</h3>}
      {!state.recent.length && <p className="stt-muted">녹음을 시작하면 확정된 발언이 여기에 표시됩니다.</p>}
      <ol>{state.recent.map(segment => <li key={segment.segmentKey}>
        <time>{new Date(segment.segmentStartAt).toLocaleTimeString('ko-KR', { timeZone: 'Asia/Seoul' })} (추정)</time>
        <p>{segment.transcript}</p>
      </li>)}</ol>
      {state.interim && <div className="stt-interim"><span>인식 중 · 변경될 수 있음</span><p>{state.interim}</p></div>}
      <footer>최근 확정 발언 30건을 표시합니다. 발언 시각은 브라우저가 전사를 처음 확인하고 확정한 때를 서버 시계 기준으로 추정합니다.</footer>
    </Transcript>
    {state.archived > 0 && <aside className="stt-archive">
      <h3>별도 보관 기록 {state.archived}건</h3>
      <p>서버 저장이 확인되지 않은 기록입니다. 다운로드한 파일은 보고서에 자동 반영되지 않습니다.</p>
      <button className="secondary" onClick={download}>기록 다운로드</button>{' '}
      {!integrated && <button className="secondary" onClick={remove}>보관 기록 삭제</button>}
    </aside>}
  </section>
}

// userId must come from /auth/me (or the authenticated application's user context).
export function SttPanel({ userId, roomId, authRevision = 0, onRecorder, ending = false }) {
  const [recorder, setRecorder] = useState(null)
  const [connection, setConnection] = useState(0)
  const previousCleanup = useRef(Promise.resolve())
  useEffect(() => { if (authRevision && recorder) recorder.retry() }, [authRevision, recorder])
  useEffect(() => {
    const key = `${userId}:${roomId}`
    const controller = new Recorder({ userId, roomId, api: sttApi(roomId), store: createStore(key),
      acquire: () => acquireTab(key), speechFactory: createSpeech })
    setRecorder(controller)
    onRecorder?.(controller)
    let cancelled = false
    previousCleanup.current.then(() => { if (!cancelled) controller.init() })
    const warn = event => {
      const value = controller.getSnapshot()
      if (value.phase === 'listening' || value.pending || value.pendingGaps || value.stopPending) {
        event.preventDefault()
        event.returnValue = ''
      }
    }
    const hide = () => controller.dispose()
    const show = event => { if (event.persisted) setConnection(value => value + 1) }
    window.addEventListener('beforeunload', warn)
    window.addEventListener('pagehide', hide)
    window.addEventListener('pageshow', show)
    return () => {
      window.removeEventListener('beforeunload', warn)
      window.removeEventListener('pagehide', hide)
      window.removeEventListener('pageshow', show)
      onRecorder?.(null)
      cancelled = true
      previousCleanup.current = controller.dispose()
    }
  }, [userId, roomId, connection, onRecorder])
  const reconnect = () => setConnection(value => value + 1)
  return <>{recorder && <RecordingView recorder={recorder} integrated={!!onRecorder} ending={ending} reconnect={reconnect} />}
    {!onRecorder && <button className="secondary stt-reconnect" onClick={() => {
      if (recorder?.getSnapshot().phase === 'listening' && !window.confirm('음성 인식을 멈추고 화면을 다시 연결할까요?')) return
      reconnect()
    }}>화면 다시 연결</button>}</>
}
