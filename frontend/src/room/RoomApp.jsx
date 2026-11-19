import React, { useEffect, useRef, useState } from 'react'
import QRCode from 'qrcode'
import { request } from '../stt/api.js'
import { draftKey, emptyDraft, readDraft, writeDraft, clearDraft, loginFor, terminal, errorText } from './state.js'
import '@fontsource/pretendard/400.css'
import '@fontsource/pretendard/600.css'
import '@fontsource/pretendard/700.css'
import './room.css'

const statusNames = { READY: '준비 중', LIVE: '진행 중', ENDED: '종료' }
const time = value => value ? new Date(value).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' }) : ''

export function RoomApp() {
  const [user, setUser] = useState(null)
  const [error, setError] = useState(null)
  const [revision, setRevision] = useState(0)
  const [loggingOut, setLoggingOut] = useState(false)
  const [logoutError, setLogoutError] = useState('')
  const [creating, setCreating] = useState(false)
  const logoutLock = useRef(false)
  const id = location.pathname.split('/').at(-1)
  useEffect(() => {
    let active = true
    request('/auth/me').then(value => { if (active) setUser(value) }).catch(e => {
      if (!active) return
      if (e.status === 401) loginFor(location.pathname)
      else setError(e)
    })
    return () => { active = false }
  }, [revision])
  async function logout() {
    if (logoutLock.current || creating) return
    const draft = readDraft(user.userId)
    if ((draft.title || draft.description || draft.uncertain) && !window.confirm('로그아웃하면 작성 중인 강연방 초안이 삭제됩니다. 로그아웃할까요?')) return
    logoutLock.current = true; setLoggingOut(true); setLogoutError('')
    try {
      try { await request('/auth/logout', { method: 'POST' }) }
      catch (e) { if (e.status !== 401) throw e } // 이미 세션이 없으면 로그아웃 목적을 달성한 상태다.
      clearDraft(user.userId)
      location.replace('/login')
    } catch {
      setLogoutError('로그아웃 결과를 확인하지 못했습니다. 연결 상태를 확인하고 다시 시도해 주세요.')
      setLoggingOut(false); logoutLock.current = false
    }
  }
  return <div className="room-app">
    <header className="room-top"><a href="/host/rooms/new" aria-label="RE:ACT 새 강연방">RE:ACT<span>강연자 공간</span></a>
      {user ? <div className="room-user-menu"><span>{user.name}</span><button className="room-secondary" disabled={loggingOut || creating} onClick={logout}>로그아웃</button></div> : <span>강연 준비부터 참여 안내까지</span>}</header>
    <main>{logoutError && <p className="room-warning" role="alert">{logoutError}</p>}
      {loggingOut ? <p role="status">로그아웃하고 있습니다.</p> : error ? <section className="room-card"><p role="alert">{errorText(error)}</p><button onClick={() => { setError(null); setRevision(v => v + 1) }}>다시 확인</button></section>
      : !user ? <p role="status">로그인 정보를 확인하고 있습니다.</p>
        : id === 'new' ? <CreateRoom user={user} onBusy={setCreating} /> : <RoomDetail key={id} id={id} />}</main>
    <footer className="room-footer">RE:ACT · 청중과 함께 만드는 강연</footer>
  </div>
}

function CreateRoom({ user, onBusy }) {
  const [draft, setDraft] = useState(() => readDraft(user.userId))
  const [restored] = useState(() => !!(draft.title || draft.description || draft.uncertain))
  const [busy, setBusy] = useState(false)
  const lock = useRef(false)
  const [error, setError] = useState('')
  const [storageError, setStorageError] = useState(false)
  function update(value) {
    setDraft(value)
    setStorageError(!writeDraft(user.userId, value))
  }
  async function submit(event) {
    event.preventDefault()
    if (lock.current) return
    if (!draft.title.trim()) { setError('강연 제목을 입력해 주세요.'); return }
    if (draft.uncertain && !window.confirm('이전 요청으로 이미 강연방이 생성됐을 수 있습니다. 중복 방이 생길 수 있는데 다시 생성할까요?')) return
    lock.current = true
    onBusy(true)
    setBusy(true)
    setError('')
    // 새로고침이 요청 도중 발생해도 생성 결과를 확정된 실패로 취급하지 않는다.
    update({ ...draft, uncertain: true })
    try {
      const room = await request('/rooms', { method: 'POST', body: { title: draft.title.trim(), description: draft.description } })
      if (!room?.roomId) throw new Error('MISSING_ROOM_ID')
      try { sessionStorage.removeItem(draftKey) } catch { /* unavailable storage already reported */ }
      location.replace(`/host/rooms/${room.roomId}`)
    } catch (e) {
      const definite = [400, 401, 403, 404, 422, 429].includes(e.status)
      update({ ...draft, uncertain: !definite || draft.uncertain })
      if (e.status === 401) { loginFor('/host/rooms/new'); return }
      setError(e.status === 400 || e.status === 422 ? '입력 내용을 확인해 주세요.'
        : e.status === 403 ? '강연방을 생성할 권한이 없습니다.'
          : e.status === 429 ? '요청이 많습니다. 잠시 후 다시 시도해 주세요.'
            : definite ? '강연방을 생성하지 못했습니다. 다시 확인해 주세요.' : '')
      setBusy(false)
      onBusy(false)
      lock.current = false
    }
  }
  return <div className="room-create">
    <div className="room-heading"><span className="room-kicker">새로운 만남의 시작</span><h1>어떤 강연을 준비하고 있나요?</h1><p>강연방을 만들고, 청중에게 참여 링크를 공유하세요.</p></div>
    <form className="room-card" onSubmit={submit}>
      {restored && <p className="room-note" role="status">작성 중이던 내용을 불러왔습니다.</p>}
      {storageError && <p role="alert">임시 저장을 사용할 수 없습니다. 새로고침하거나 로그인 화면으로 이동하면 작성 내용이 사라질 수 있습니다.</p>}
      <label htmlFor="room-title">강연 제목 <span className="room-required">필수</span></label>
      <input id="room-title" maxLength={200} value={draft.title} required disabled={busy} placeholder="예: Redis로 시작하는 캐시 이야기"
        onChange={e => update({ ...draft, title: e.target.value })} aria-describedby="title-count create-error" />
      <small id="title-count" className="room-count">{draft.title.length} / 200</small>
      <p id="create-error" role={error ? 'alert' : undefined}>{error}</p>
      <label htmlFor="room-description">강연 설명 <span className="room-optional">선택</span></label>
      <textarea id="room-description" maxLength={10000} rows={6} value={draft.description} disabled={busy} placeholder="청중에게 전하고 싶은 강연 소개를 적어주세요."
        onChange={e => update({ ...draft, description: e.target.value })} aria-describedby="description-count" />
      <small id="description-count" className="room-count">{draft.description.length.toLocaleString()} / 10,000</small>
      {!busy && draft.uncertain && <div className="room-warning" role="alert"><strong>생성 여부를 확인하지 못했습니다.</strong><p>서버에서 이미 방을 만들었을 수 있습니다. 다시 생성하면 중복 방이 생길 수 있습니다.</p></div>}
      <div className="room-form-actions"><button type="button" className="room-secondary" disabled={busy} onClick={() => {
        if (window.confirm('작성 내용과 임시 저장 내용을 지울까요? 이미 생성된 강연방은 삭제되지 않습니다.')) { update({ ...emptyDraft }); setError('') }
      }}>입력 초기화</button><button disabled={busy}>{busy ? '강연방 생성 중…' : '강연방 만들기'}</button></div>
      <p className="room-caption">방을 만들어도 강연은 자동으로 시작되지 않습니다.</p>
    </form>
  </div>
}

function RoomDetail({ id }) {
  const [room, setRoom] = useState(null)
  const [error, setError] = useState(null)
  const [revision, setRevision] = useState(0)
  const [modal, setModal] = useState(false)
  const [accessError, setAccessError] = useState(null)
  useEffect(() => {
    if (accessError) return
    let cancelled = false, timer, running = false, stopped = false
    async function load() {
      if (cancelled || running || stopped || document.hidden) return
      running = true
      clearTimeout(timer)
      try {
        const value = await request(`/rooms/${encodeURIComponent(id)}`)
        if (cancelled) return
        setRoom(value); setError(null)
        if (value.status === 'ENDED') { stopped = true; setModal(false) }
      } catch (e) {
        if (cancelled) return
        stopped = true; setModal(false); setError(e)
        if (terminal(e)) setRoom(null)
        if (e.status === 401) loginFor(location.pathname)
      } finally {
        running = false
        if (!cancelled && !stopped && !document.hidden) timer = setTimeout(load, 5000)
      }
    }
    function visibility() { clearTimeout(timer); if (!document.hidden) load() }
    load()
    document.addEventListener('visibilitychange', visibility)
    return () => { cancelled = true; clearTimeout(timer); document.removeEventListener('visibilitychange', visibility) }
  }, [id, revision, accessError])
  const fatal = accessError || (terminal(error || {}) ? error : null)
  if (fatal) return <section className="room-card"><h1>강연방을 표시할 수 없습니다</h1><p role="alert">{errorText(fatal)}</p><a href="/host/rooms/new">새 강연방 만들기</a></section>
  return <>
    <a className="room-back" href="/host/rooms/new">＋ 새 강연방 만들기</a>
    {error && <div className="room-warning" role="alert">최신 상태를 확인하지 못했습니다. 참여 안내를 잠시 숨겼습니다. <button className="room-secondary" onClick={() => setRevision(v => v + 1)}>다시 확인</button></div>}
    {!room ? !error && <p role="status">강연 정보를 불러오고 있습니다.</p> : <>
      <div className="room-heading"><span className={`room-badge room-${room.status}`}>{statusNames[room.status] || '상태 확인 필요'}</span><h1>{room.title}</h1><p>{room.status === 'ENDED' ? '종료된 강연입니다. 새로운 참여는 불가능합니다.' : room.status === 'LIVE' ? '강연이 진행 중입니다.' : '청중에게 참여 링크를 공유하세요.'}</p></div>
      <div className={`room-grid ${room.status === 'ENDED' ? 'room-ended' : ''}`}>
        <section className="room-card"><span className="room-kicker">LECTURE</span><h2>강연 정보</h2><p className="room-description">{room.description || '등록된 강연 설명이 없습니다.'}</p><dl>
          <dt>생성 시각</dt><dd>{time(room.createdAt)}</dd>
          {room.startedAt && <><dt>시작 시각</dt><dd>{time(room.startedAt)}</dd></>}
          {room.endedAt && <><dt>종료 시각</dt><dd>{time(room.endedAt)}</dd></>}
        </dl><p className="room-caption">모든 시각은 한국 시간(KST) 기준입니다.</p></section>
        {!error && ['READY', 'LIVE'].includes(room.status) && <Invitation id={id} modal={modal} setModal={setModal} onAccessError={e => {
          setModal(false); setRoom(null); setAccessError(e)
          if (e.status === 401) loginFor(location.pathname)
        }} />}
      </div>
    </>}
  </>
}

function Invitation({ id, modal, setModal, onAccessError }) {
  const [info, setInfo] = useState(null)
  const [qr, setQr] = useState('')
  const [error, setError] = useState(false)
  const [revision, setRevision] = useState(0)
  const [copy, setCopy] = useState('')
  const access = useRef(onAccessError)
  access.current = onAccessError
  useEffect(() => {
    let active = true
    setError(false)
    request(`/rooms/${id}/invitation`).then(async value => {
      const image = await QRCode.toDataURL(value.qrText, { width: 640, margin: 4, errorCorrectionLevel: 'M' })
      if (active) { setInfo(value); setQr(image) }
    }).catch(e => {
      if (!active) return
      if (terminal(e)) access.current(e)
      else setError(true)
    })
    return () => { active = false }
  }, [id, revision])
  const dialog = useRef(null)
  const openButton = useRef(null)
  useEffect(() => {
    if (modal && dialog.current && !dialog.current.open) dialog.current.showModal()
    if (!modal && dialog.current?.open) { dialog.current.close(); openButton.current?.focus() }
  }, [modal])
  return <section className="room-card room-invite"><span className="room-kicker">INVITATION</span><h2>청중 초대</h2>
    {error ? <><p role="alert">참여 정보를 불러오지 못했습니다.</p><button onClick={() => setRevision(v => v + 1)}>다시 불러오기</button></>
      : !info ? <p role="status">참여 정보를 불러오고 있습니다.</p> : <>
        <img className="room-qr" src={qr} alt="청중 참여 링크 QR코드" />
        <button className="room-secondary" ref={openButton} onClick={() => setModal(true)}>QR 크게 보기</button>
        <label htmlFor="join-link">참여 링크</label><div className="room-copy"><input id="join-link" readOnly value={info.joinUrl} onFocus={e => e.target.select()} /><button onClick={async () => {
          try { await navigator.clipboard.writeText(info.joinUrl); setCopy('참여 링크를 복사했습니다.') }
          catch { setCopy('복사하지 못했습니다. 링크를 선택해 직접 복사해 주세요.') }
        }}>링크 복사</button></div><p role="status" className="room-caption">{copy}</p>
        <p className="room-code">참여 코드 <strong>{info.joinCode}</strong></p>
        <dialog ref={dialog} className="room-dialog" aria-labelledby="qr-title" onCancel={() => setModal(false)} onClose={() => setModal(false)}>
          <h2 id="qr-title">QR코드로 강연에 참여하세요</h2><img src={qr} alt="확대된 청중 참여 QR코드" /><p>참여 코드 · {info.joinCode}</p><button onClick={() => setModal(false)}>닫기</button>
        </dialog>
      </>}
  </section>
}
