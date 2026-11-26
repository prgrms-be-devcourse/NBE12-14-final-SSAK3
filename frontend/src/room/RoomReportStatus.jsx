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
  const { status, busy, error, delayed, retryAvailable, failureCategory, create, check, retry } = useReport(source)
  const delayMessage = status === 'PENDING'
    ? '보고서 대기가 2분을 넘었어요. 이 화면을 닫아도 기록이 준비되면 생성을 시작해요.'
    : '보고서 생성을 시작한 지 2분이 지났어요. 이 화면을 닫아도 내 강연 기록에서 결과를 확인할 수 있어요.'
  return <section className="room-card room-report-status" aria-labelledby="room-report-title">
    <div>
      <h2 id="room-report-title">회고 보고서</h2>
      {error ? <p role="alert">{error}</p> : <p role="status">{delayed ? delayMessage : messages[status]}</p>}
      {!error && status === 'FAILED' && <>
        <p>{failureCategory === 'AI_SERVICE' ? 'AI 요약을 요청하는 과정에서 문제가 생겨 이번 강연 요약을 만들지 못했어요.'
          : failureCategory === 'AI_OUTPUT' ? 'AI가 만든 강연 요약의 형식이나 근거를 확인할 수 없어 요약을 표시하지 않았어요.'
          : '이번 보고서의 AI 요약을 제공할 수 없어요. 자세한 실패 원인은 확인되지 않았어요.'}</p>
        <p>이 강연방에 저장된 발언과 채팅, 투표 결과는 아래에서 계속 확인할 수 있어요.</p>
        <p>{retryAvailable ? '보고서 생성을 한 번 더 요청할 수 있어요.' : '이 보고서는 생성을 다시 요청할 수 없어요.'}</p>
      </>}
    </div>
    {error ? <button className="room-secondary" onClick={check} disabled={busy}>다시 불러오기</button>
      : status === 'COMPLETED' ? <Link to={`/host/rooms/${roomId}/report`}>회고 보고서 보기</Link>
      : status === 'FAILED' && retryAvailable ? <button onClick={retry} disabled={busy}>보고서 생성 다시 시도</button>
      : status === 'NOT_CREATED' && <button onClick={create} disabled={busy}>보고서 만들기</button>}
  </section>
}
