import React, { useEffect, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { loginFor } from '../room/state.js'
import { request } from '../stt/api.js'
import { ReportLayout } from './ReportPage.jsx'
import { ReportView } from './ReportView.jsx'

export function HostReportPage() {
  const { roomId } = useParams()
  return <HostReport key={roomId} roomId={roomId} />
}

function HostReport({ roomId }) {
  const navigate = useNavigate()
  const validId = /^[1-9]\d*$/.test(roomId)
  const roomPath = `/host/rooms/${roomId}`
  const room = useRef(null)
  const [attempt, setAttempt] = useState(0)
  const [view, setView] = useState({})

  useEffect(() => {
    if (!validId) return
    let active = true
    const path = `/rooms/${roomId}`
    async function read() {
      if (!active) return
      try {
        // 본문 API에 포함되지 않는 제목·강연 시각은 이 페이지에서 한 번만 읽는다.
        if (!room.current) {
          const metadata = await request(path)
          if (!active) return
          if (String(metadata?.roomId) !== roomId) throw new Error('INVALID_ROOM_RESPONSE')
          room.current = metadata
        }
        const report = await request(`${path}/reports`)
        if (!active) return
        if (String(report?.roomId) !== roomId) throw new Error('INVALID_REPORT_RESPONSE')
        if (['PENDING', 'PROCESSING', 'FAILED'].includes(report.status)) {
          navigate(roomPath, { replace: true })
        } else if (report.status === 'COMPLETED' && report.content?.overview) {
          setView({ room: room.current, content: report.content })
        } else {
          throw new Error('INVALID_REPORT_RESPONSE')
        }
      } catch (error) {
        if (!active) return
        if (error.status === 401) loginFor(`${roomPath}/report`)
        else if (error.status === 404 && error.code === 'REPORT_NOT_FOUND') navigate(roomPath, { replace: true })
        else if (error.status === 409 && error.code === 'ROOM_NOT_ENDED') setView({ message: '강연이 끝나면 회고 보고서가 자동으로 생성됩니다.' })
        else if (error.status === 403) setView({ message: '이 강연방을 볼 권한이 없습니다.' })
        else if (error.status === 404 && error.code === 'ROOM_NOT_FOUND') setView({ message: '강연방을 찾을 수 없습니다.' })
        else setView({ message: '보고서를 불러오지 못했어요.', retry: true })
      }
    }
    queueMicrotask(read)
    return () => { active = false }
  }, [roomId, roomPath, validId, attempt, navigate])

  const message = validId ? view.message : '강연방을 찾을 수 없습니다.'
  return <div className="report-app">
    <ReportLayout room={view.room} completed={!!view.content} backTo={validId ? roomPath : null}>
      {view.content ? <ReportView room={view.room} content={view.content} /> : <section className="report-state">
        <p role={message ? 'alert' : 'status'}>{message || '회고 보고서를 불러오고 있어요.'}</p>
        {view.retry && <button onClick={() => { setView({}); setAttempt(value => value + 1) }}>다시 불러오기</button>}
      </section>}
    </ReportLayout>
  </div>
}
