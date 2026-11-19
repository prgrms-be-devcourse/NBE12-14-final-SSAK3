import React, { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { request } from '../stt/api.js'

export function EntryPage() {
  const navigate = useNavigate()
  const [error, setError] = useState(false)
  const [revision, setRevision] = useState(0)
  useEffect(() => {
    let active = true
    request('/auth/me').then(() => {
      if (active) navigate('/host/rooms/new', { replace: true })
    }).catch(e => {
      if (!active) return
      if (e.status === 401) navigate('/login', { replace: true })
      else setError(true)
    })
    return () => { active = false }
  }, [navigate, revision])
  return <div className="room-app"><main><section className="room-card">
    <h1>RE:ACT</h1>
    {error ? <><p role="alert">로그인 상태를 확인하지 못했습니다. 서버 연결을 확인해 주세요.</p>
      <button onClick={() => { setError(false); setRevision(value => value + 1) }}>다시 확인</button></>
      : <p role="status">로그인 상태를 확인하고 있습니다.</p>}
  </section></main></div>
}
