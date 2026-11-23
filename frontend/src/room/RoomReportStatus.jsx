import React, { useMemo } from 'react'
import { Link } from 'react-router-dom'
import { useReport } from '../report/useReport.js'
import { roomReportSource } from './reportStatus.js'

const messages = {
  LOADING: '보고서 상태를 확인하고 있어요.',
  AWAITING_RECEIPT: '보고서 자동 접수를 확인하고 있어요.',
  NOT_CREATED: '아직 보고서가 접수되지 않았어요.',
  PENDING: '강연 기록을 정리하고 있어요.',
  PROCESSING: '회고 보고서를 만들고 있어요.',
  COMPLETED: '회고 보고서가 완성됐어요.',
  FAILED: '보고서 생성에 실패했습니다.',
  LIVE: '강연이 끝나면 회고 보고서가 자동으로 생성됩니다.',
}

export function RoomReportStatus({ roomId, endedAt, onAccessError }) {
  const source = useMemo(() => roomReportSource(roomId, endedAt, onAccessError), [roomId, endedAt, onAccessError])
  const { status, busy, error, create, check } = useReport(source)
  return <section className="room-card room-report-status" aria-labelledby="room-report-title">
    <div>
      <h2 id="room-report-title">회고 보고서</h2>
      {error ? <p role="alert">{error}</p> : <p role="status">{messages[status]}</p>}
    </div>
    {error ? <button className="room-secondary" onClick={check} disabled={busy}>다시 불러오기</button>
      : status === 'COMPLETED' ? <Link to={`/host/rooms/${roomId}/report`}>회고 보고서 보기</Link>
      : status === 'NOT_CREATED' && <button onClick={create} disabled={busy}>보고서 만들기</button>}
  </section>
}
