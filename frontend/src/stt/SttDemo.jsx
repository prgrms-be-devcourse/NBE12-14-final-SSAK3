import React, { useEffect, useState } from 'react'
import { request } from './api.js'
import { SttPanel } from './SttPanel.jsx'

export function SttDemo() {
  const [user, setUser] = useState(null)
  const [room, setRoom] = useState('')
  const [roomInput, setRoomInput] = useState(new URLSearchParams(location.search).get('roomId') || '')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [authRevision, setAuthRevision] = useState(0)
  useEffect(() => { request('/auth/me').then(setUser).catch(() => {}) }, [])
  async function login(event) {
    event.preventDefault()
    setBusy(true)
    setError('')
    const form = event.currentTarget
    const data = new FormData(form)
    try {
      await request('/auth/login', { method: 'POST', body: { email: data.get('email'), password: data.get('password') } })
      const authenticated = await request('/auth/me')
      if (user && String(user.userId) !== String(authenticated.userId)) setRoom('')
      setUser(authenticated)
      setAuthRevision(value => value + 1)
      form.reset()
    } catch { setError('로그인하지 못했습니다. 계정 정보와 서버 연결을 확인해 주세요.') }
    finally { setBusy(false) }
  }
  return <main className="stt-demo">
    <header className="stt-brand"><h1>RE:ACT</h1><p>강연자 STT 연결 확인</p></header>
    <details className="stt-login" open={!user}>
      <summary>{user ? `${user.name} · 로그인 갱신` : '강연자 로그인'}</summary>
      <form onSubmit={login}>
        <label>이메일<input name="email" type="email" required autoComplete="username" /></label>
        <label>비밀번호<input name="password" type="password" required autoComplete="current-password" /></label>
        <button disabled={busy}>로그인</button>
      </form>
      {error && <p role="alert">{error}</p>}
    </details>
    {user && <form className="stt-room" onSubmit={event => {
      event.preventDefault()
      if (!/^[1-9]\d*$/.test(roomInput)) return
      if (room && room !== roomInput && !window.confirm('현재 녹음 화면을 닫고 다른 강연방에 연결할까요?')) return
      setRoom(roomInput)
    }}>
      <label>강연방 ID<input aria-label="강연방 ID" value={roomInput} onChange={event => setRoomInput(event.target.value)}
        inputMode="numeric" pattern="[1-9][0-9]*" required /></label>
      <button disabled={busy}>강연방 연결</button>
      <p>본인이 소유한 LIVE 강연방을 연결하세요. 강연방 생성·시작은 기존 기능에서 진행합니다.</p>
    </form>}
    {user && room && <SttPanel key={`${user.userId}:${room}`} userId={user.userId} roomId={room} authRevision={authRevision} />}
  </main>
}
