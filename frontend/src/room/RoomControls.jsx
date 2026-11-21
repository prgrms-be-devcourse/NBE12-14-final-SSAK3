import React, { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { request } from '../stt/api.js'
import { terminal } from './state.js'

const subscribeNone = () => () => {}
const noRecorder = () => null

export function RoomControls({ room, userId, recorder, onRoom, onAccessError, onEnding, offline }) {
  const base = `/rooms/${room.roomId}`
  const key = `react:host-end:${userId}:${room.roomId}`
  const [phase, setPhase] = useState(() => {
    try {
      const saved = room.status === 'LIVE' && sessionStorage.getItem(key)
      if (saved === 'end') return 'resume-end'
      return saved === 'save' ? 'resume' : 'idle'
    }
    catch { return 'idle' }
  })
  const [confirming, setConfirming] = useState(false)
  const dialog = useRef(null)
  const trigger = useRef(null)
  const active = useRef(false)
  const working = useRef(false)
  const latest = useRef(room.status)
  latest.current = room.status
  const view = useSyncExternalStore(recorder?.subscribe || subscribeNone, recorder?.getSnapshot || noRecorder)

  function remember(value) {
    try {
      if (value) sessionStorage.setItem(key, value)
      else sessionStorage.removeItem(key)
    } catch { /* On reload the server status is still authoritative. */ }
  }

  useEffect(() => {
    active.current = true
    return () => { active.current = false }
  }, [])
  useEffect(() => {
    if (room.status === 'ENDED') {
      remember(null)
      setConfirming(false)
      setPhase('idle')
    } else if (room.status === 'LIVE') {
      setPhase(p => ['starting', 'check-start', 'retry-start'].includes(p) ? 'idle' : p)
    }
  }, [room.status])
  useEffect(() => {
    onEnding(room.status === 'LIVE' && ['saving', 'retry-save', 'ending', 'retry-end', 'check-end', 'resume', 'resume-end'].includes(phase))
  }, [room.status, phase, onEnding])
  useEffect(() => {
    if (confirming) dialog.current?.showModal()
  }, [confirming])

  function cancel() {
    dialog.current?.close()
    setConfirming(false)
    trigger.current?.focus()
  }

  async function reconcile(action) {
    setPhase(`check-${action}`)
    try {
      const value = await request(base)
      if (!active.current) return
      onRoom(value)
      const pending = action === 'start' ? 'READY' : 'LIVE'
      setPhase(value.status === pending ? `retry-${action}` : 'idle')
    } catch (e) {
      if (active.current && terminal(e)) onAccessError(e)
    }
  }

  async function send(action) {
    setPhase(action === 'start' ? 'starting' : 'ending')
    if (action === 'end') remember('end')
    try {
      const value = await request(`${base}/${action}`, { method: 'POST' })
      if (!active.current) return
      if (value?.status !== (action === 'start' ? 'LIVE' : 'ENDED')) throw new Error('UNKNOWN_STATUS')
      onRoom(value)
      setPhase('idle')
    } catch (e) {
      if (!active.current) return
      if (terminal(e)) onAccessError(e)
      else await reconcile(action)
    }
  }

  async function run(job) {
    if (working.current) return
    working.current = true
    try { await job() } finally { working.current = false }
  }

  async function finish() {
    setConfirming(false)
    remember('save')
    setPhase('saving')
    try {
      const saved = await recorder?.finishForRoomEnd()
      if (!active.current || latest.current !== 'LIVE') return
      if (saved) await send('end')
      else setPhase('retry-save')
    } catch {
      if (active.current) setPhase('retry-save')
    }
  }

  // A background STT retry may finish after the initial stop returned. A reload restores
  // 'resume', so only the still-open, confirmed flow can close the room automatically.
  useEffect(() => {
    if (phase === 'retry-save' && view?.readyToCloseRoom && room.status === 'LIVE') {
      void run(() => send('end'))
    }
  }, [phase, view?.readyToCloseRoom, room.status])

  if (room.status === 'ENDED') return null
  const checking = phase === 'check-end' || phase === 'check-start'
  if (checking) return <div className="room-warning" role="status">
    방 상태를 확인하고 있습니다. 연결이 끊겼다면 다시 확인해 주세요.{' '}
    <button onClick={() => run(() => reconcile(phase === 'check-start' ? 'start' : 'end'))}>상태 다시 확인</button>
  </div>
  if (room.status === 'READY') return <div className="room-lifecycle">
    <button disabled={offline || phase === 'starting'} onClick={() => run(() => send('start'))}>
      {phase === 'starting' ? '시작 중…' : phase === 'retry-start' ? '강연 시작 다시 시도' : '강연 시작'}</button>
  </div>
  if (room.status !== 'LIVE') return null
  return <div className="room-lifecycle">
    {phase === 'retry-end' ? <><span role="status">녹음 저장은 완료됐고 강연방은 열려 있습니다.</span>
      <button disabled={offline} onClick={() => run(() => send('end'))}>강연 종료 다시 시도</button></>
    : ['resume', 'resume-end', 'retry-save'].includes(phase) ? <>
      <span role="status">{phase !== 'retry-save' ? '진행 중이던 강연 종료를 확인해 주세요.'
        : view?.archived ? '서버가 저장을 거절한 기록이 있습니다. 발언 기록에서 보관 내용을 확인해 주세요.'
        : '남은 기록의 저장과 녹음 종료를 확인해야 합니다.'}</span>
      <button disabled={offline || !recorder || view?.busy} onClick={() => run(phase === 'resume-end' ? () => send('end') : finish)}>
        {phase === 'retry-save' ? '재시도 후 강연 종료' : '강연 종료 계속하기'}</button></>
    : <button ref={trigger} className="room-secondary room-end" disabled={offline || !recorder || view?.busy || ['ending','saving'].includes(phase)}
        onClick={() => setConfirming(true)}>
        {phase === 'saving' ? '마지막 기록 저장 중…' : phase === 'ending' ? '강연 종료 중…' : '강연 종료'}</button>}
    {confirming && <dialog ref={dialog} className="room-dialog room-end-dialog" aria-labelledby="end-title"
        onCancel={cancel}>
      <h2 id="end-title">강연을 종료할까요?</h2><p>마지막 발언을 저장한 뒤 방을 닫습니다. 종료하면 청중은 새 채팅이나 투표 응답을 보낼 수 없습니다.</p>
      <div><button autoFocus className="room-secondary" onClick={cancel}>계속 진행</button>
        <button onClick={() => run(finish)}>강연 종료</button></div>
    </dialog>}
  </div>
}
