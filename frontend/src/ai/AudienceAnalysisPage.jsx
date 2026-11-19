import React, { useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
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
  const [state, setState] = useState(initialFeed)
  const feed = useRef(null)
  useEffect(() => {
    const instance = createAudienceFeed({ roomId, request, onChange: setState,
      onUnauthenticated: () => loginFor(`/dev/rooms/${roomId}/ai`) })
    feed.current = instance
    const visibility = () => instance.visibilityChanged()
    document.addEventListener('visibilitychange', visibility)
    instance.start()
    return () => { instance.dispose(); document.removeEventListener('visibilitychange', visibility) }
  }, [roomId])
  const actions = {
    reconnect: () => feed.current?.reconnect(), more: () => feed.current?.more(),
    toggleSource: id => feed.current?.toggleSource(id), retrySource: id => feed.current?.retrySource(id),
    showLatest: () => feed.current?.showLatest(),
    closeSources: ids => feed.current?.closeSources(ids),
  }
  return <div className="room-app ai-app">
    <header className="room-top"><Link to="/">RE:ACT <span>청중의 생각을 강연에</span></Link>
      <span>AI 요약 확인용 화면</span></header>
    <main>
      {state.fatal ? <section className="room-card" role="alert">
        <h1>{state.fatal === 401 ? '로그인이 필요합니다' : state.fatal === 403
          ? '이 강연방을 조회할 권한이 없습니다' : '강연방을 찾을 수 없습니다'}</h1>
        <Link to="/host/rooms/new">새 강연방 만들기</Link>
      </section> : <>
        <Link className="room-back" to={`/host/rooms/${roomId}`}>← 강연방 상세로 돌아가기</Link>
        <div className="room-heading"><span className="room-kicker">AUDIENCE INSIGHTS</span>
          <h1>{state.room?.title || '청중 질문·의견 요약'}</h1>
          <p>청중이 남긴 의견을 살펴보고, 근거 원문을 함께 확인하세요.</p></div>
        <AudienceAnalysisPanel state={state} actions={actions} />
      </>}
    </main>
  </div>
}
