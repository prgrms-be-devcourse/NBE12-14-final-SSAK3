
import React, { useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Brand } from '../ui/Brand.jsx'
import { request } from '../stt/api.js'
import { loginFor } from '../room/state.js'
import { createAudienceFeed, initialFeed } from './feed.js'
import { AudienceAnalysisPanel } from './AudienceAnalysisPanel.jsx'
import '../room/room.css'
import './audience.css'

export function AudienceAnalysisPage() {
  const { roomId } = useParams()
  if (!/^[1-9]\d*$/.test(roomId || '')) return <div className="room-app"><main>
    <h1>올바르지 않은 강연방 주소입니다.</h1><Link to="/host/rooms/new">새 강연방 만들기</Link>
  </main></div>
  return <AudienceRoom key={roomId} roomId={roomId} />
}

function AudienceRoom({ roomId }) {
  return <div className="room-app ai-app">
    <header className="ai-top"><Link to="/" aria-label="RE:ACT 홈"><Brand /></Link>
      <span>AI 요약 확인용 화면</span></header>
    <main><Link className="room-back" to={`/host/rooms/${roomId}`}>← 강연방 상세로 돌아가기</Link>
      <AudienceRoomPanel roomId={roomId} standalone />
    </main>
  </div>
}

export function AudienceRoomPanel({ roomId, standalone = false, onAccessError, renderPanel }) {
  const [state, setState] = useState(initialFeed)
  const feed = useRef(null)
  const access = useRef(onAccessError)
  access.current = onAccessError
  useEffect(() => {
    if (state.fatal && state.fatal !== 401) access.current?.({ status: state.fatal })
  }, [state.fatal])
  useEffect(() => {
    const instance = createAudienceFeed({ roomId, request, onChange: setState, knownRoom: standalone ? null : { roomId },
      onUnauthenticated: () => loginFor(location.pathname) })
    feed.current = instance
    const visibility = () => instance.visibilityChanged()
    document.addEventListener('visibilitychange', visibility)
    queueMicrotask(() => instance.start())
    return () => { instance.dispose(); document.removeEventListener('visibilitychange', visibility) }
  }, [roomId, standalone])
  const actions = {
    reconnect: () => feed.current?.reconnect(), more: () => feed.current?.more(),
    toggleSource: id => feed.current?.toggleSource(id), retrySource: id => feed.current?.retrySource(id),
    showLatest: () => feed.current?.showLatest(),
    closeSources: ids => feed.current?.closeSources(ids),
    refresh: () => feed.current?.visibilityChanged(),
  }
  if (state.fatal) return <section className="room-card" role="alert">
    <h2>{state.fatal === 401 ? '로그인이 필요합니다' : state.fatal === 403
        ? '이 강연방을 조회할 권한이 없습니다' : '강연방을 찾을 수 없습니다'}</h2>
    <Link to="/host/rooms/new">새 강연방 만들기</Link>
  </section>
  return <>{standalone && <div className="room-heading"><h1>{state.room?.title || '청중 질문·의견 요약'}</h1>
    <p>청중이 남긴 의견을 살펴보고, 근거 원문을 함께 확인하세요.</p></div>}
    {renderPanel
        ? renderPanel({ state, actions })
        : <AudienceAnalysisPanel state={state} actions={actions} />}
  </>
}
