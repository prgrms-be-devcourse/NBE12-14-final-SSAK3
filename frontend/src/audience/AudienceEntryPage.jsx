import React, { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { ChatPanel } from '../chat/ChatPanel.jsx'
import { UnderstandingCheck } from '../poll/UnderstandingCheck.jsx'
import { randomUuid } from '../uuid.js'
import { clearAttempt, endReason, joinRoom, loadAttempt, readParticipation, saveAttempt } from './participation.js'
import { watchRoom } from './watchRoom.js'
import './participation.css'

function AudienceLayout({ children, live = false }) {
  return <div className={live ? 'participation participation-live' : 'participation'}>
    <header><strong>RE:ACT</strong><span>청중 참여</span></header>
    <main>{children}</main>
  </div>
}

export function ParticipationEndedPage({ reason }) {
  const title = useRef(null)
  useEffect(() => { title.current.focus() }, [])
  return <AudienceLayout>
    <h1 ref={title} tabIndex={-1} aria-describedby="participation-end-reason">참여 종료</h1>
    <p id="participation-end-reason" role="alert">{reason === 'ended' ? '강연이 종료되었습니다' : '더 이상 참여할 수 없는 강연방입니다'}</p>
  </AudienceLayout>
}

export function AudienceEntryPage() {
  const { joinCode } = useParams()
  return <JoinRoomPage key={joinCode} code={joinCode} />
}

function JoinRoomPage({ code }) {
  const navigate = useNavigate()
  const [room, setRoom] = useState(null)
  const [participant, setParticipant] = useState(null)
  const [lookupFailed, setLookupFailed] = useState(false)
  const [lookup, setLookup] = useState(0)
  const finish = useCallback(reason => {
    clearAttempt(code)
    navigate(`/participation-ended/${reason}`, { replace: true })
  }, [code, navigate])

  useEffect(() => {
    let active = true
    async function load() {
      if (!active) return
      try {
        const result = await readParticipation(code)
        if (!active) return
        if (result.room.status === 'ENDED') { finish('ended'); return }
        if (result.participant) clearAttempt(code)
        setParticipant(result.participant)
        setRoom(result.room)
      } catch (error) {
        if (!active) return
        const reason = endReason(error)
        if (reason) finish(reason)
        else setLookupFailed(true)
      }
    }
    queueMicrotask(load)
    return () => { active = false }
  }, [code, finish, lookup])

  const updateStatus = useCallback(status => {
    // A REST request that began before the LIVE event can still return READY.
    setRoom(current => current.status === 'READY' && status === 'LIVE' ? { ...current, status } : current)
  }, [])
  function joined(result) {
    clearAttempt(code)
    if (result.roomStatus === 'ENDED') { finish('ended'); return }
    updateStatus(result.roomStatus)
    setParticipant(result)
  }
  if (!room) return <AudienceLayout>
    <h1>강연방 입장</h1>
    {lookupFailed ? <>
      <p role="alert">강연방 정보를 불러오지 못했습니다.</p>
      <button onClick={() => { setLookupFailed(false); setLookup(n => n + 1) }}>다시 불러오기</button>
    </> : <p role="status">강연방 확인 중</p>}
  </AudienceLayout>
  return <AudienceLayout live={Boolean(participant) && room.status === 'LIVE'}>
    {participant ? <JoinedRoom code={code} room={room} participant={participant} finish={finish}
      onStatus={updateStatus} setParticipant={setParticipant} /> : <>
      <h1>{room.title}</h1>
      {room.description && <p className="participation-description">{room.description}</p>}
      <JoinForm code={code} roomId={room.roomId} onJoined={joined} finish={finish} />
    </>}
  </AudienceLayout>
}

function JoinedRoom({ code, room, participant, finish, onStatus, setParticipant }) {
  const [connected, setConnected] = useState(false)
  const watcher = useRef(null)
  useEffect(() => {
    const subscription = watchRoom({ code, roomId: room.roomId, onStatus, onEnded: finish,
      onParticipant: setParticipant, onConnection: setConnected })
    watcher.current = subscription
    return () => { watcher.current = null; subscription.close() }
  }, [code, room.roomId, finish, onStatus, setParticipant])
  const live = room.status === 'LIVE'
  return <>
    <p className="participation-title">{room.title}</p>
    <h1 aria-live="polite">{live ? '강연 진행 중' : '강연 시작 대기 중'}</h1>
    <p>{participant.nickname}님으로 입장했습니다.</p>
    {!live && <p>강연자가 시작하면 이 화면에서 바로 참여할 수 있습니다.</p>}
    {!connected && <p role="status" className="participation-notice">연결 확인 중</p>}
    {live && <UnderstandingCheck roomId={room.roomId} />}
    {live && <ChatPanel roomId={room.roomId} live viewer={{ type: 'PARTICIPANT', id: participant.participantId }}
      since={participant.joinedAt} onAccessError={() => watcher.current?.reconcile()} />}
  </>
}

function JoinForm({ code, roomId, onJoined, finish }) {
  const [attempt, setAttempt] = useState(() => loadAttempt(code, roomId))
  const [nickname, setNickname] = useState(attempt?.nickname ?? '')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const submitting = useRef(false)
  const mounted = useRef(false)
  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false }
  }, [])

  async function submit(event) {
    event.preventDefault()
    if (submitting.current || !nickname.trim()) return
    submitting.current = true
    setBusy(true)
    setError('')
    let next
    try {
      next = { roomId, nickname: nickname.trim(), key: attempt?.key ?? randomUuid() }
      saveAttempt(code, next)
    } catch {
      setError('입장 정보를 임시 저장할 수 없습니다. 브라우저의 사이트 저장 설정을 확인해 주세요.')
      setBusy(false)
      submitting.current = false
      return
    }
    try {
      const result = await joinRoom(code, next)
      if (mounted.current) onJoined(result)
    } catch (error) {
      if (!mounted.current) return
      const reason = endReason(error)
      if (reason) { finish(reason); return }
      if (error.status === 409 && error.code === 'CONFLICT') {
        const expired = { ...next, key: null }
        setAttempt(expired)
        try { saveAttempt(code, expired) } catch { clearAttempt(code) }
      } else if (error.code === 'INVALID_NICKNAME') {
        clearAttempt(code)
        setAttempt(null)
        setError('닉네임은 공백을 제외하고 1자 이상, 50자 이내로 입력해 주세요.')
      } else {
        setNickname(next.nickname)
        setAttempt(next)
      }
    } finally {
      submitting.current = false
      if (mounted.current) setBusy(false)
    }
  }
  let label = '입장'
  if (attempt) label = attempt.key ? '입장 다시 시도' : '새로 입장'
  return <form onSubmit={submit}>
    <label htmlFor="audience-nickname">닉네임</label>
    <input id="audience-nickname" type="text" autoComplete="nickname" maxLength={50}
      value={nickname} readOnly={Boolean(attempt?.key) || busy}
      onChange={event => { setNickname(event.target.value); setError('') }} required />
    {attempt && <p role="status">{attempt.key ? '이전 입장 결과를 확인하지 못했습니다. 같은 닉네임으로 다시 시도해 주세요.'
      : '이전 입장 요청이 만료되었습니다. 새로 입장해 주세요.'}</p>}
    {error && <p role="alert">{error}</p>}
    <button type="submit" disabled={busy || !nickname.trim()}>{busy ? '입장 중' : label}</button>
  </form>
}
