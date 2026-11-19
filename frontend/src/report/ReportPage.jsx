import React from 'react'
import { ReportView, elapsed } from './ReportView.jsx'
import { useReport } from './useReport.js'
import './report.css'

const messages = {
  LOADING: ['보고서를 확인하고 있습니다', '잠시만 기다려 주세요.'],
  LIVE: ['강연이 진행 중입니다', '강연 종료 후 보고서를 생성할 수 있습니다.'],
  NOT_READY: ['강연 기록을 정리하고 있습니다', '발언 저장·투표 집계·청중 분석이 정리되면 생성할 수 있습니다.'],
  NOT_CREATED: ['강연을 돌아볼 준비가 됐어요', '수집된 질문과 반응을 바탕으로 회고 보고서를 생성해 보세요.'],
  PENDING: ['보고서 생성 대기 중', '요청이 접수되었습니다. 화면을 보고 있는 동안 5초마다 상태를 확인합니다.'],
  PROCESSING: ['보고서를 생성하고 있습니다', '질문과 반응의 근거를 연결하고 있습니다. 완료되면 결과를 보여드립니다.'],
  FAILED: ['보고서 생성에 실패했습니다.', '자동 재생성은 하지 않습니다. 기존 발언과 청중 기록은 유지됩니다.'],
  ACCESS_DENIED: ['보고서를 열 수 없습니다', '로그인 상태와 강연방 접근 권한을 확인해 주세요.'],
}
export function ReportPage({ source, room }) {
  const state = useReport(source)
  const message = messages[state.status] || ['보고서 상태를 확인할 수 없습니다', '상태를 다시 확인해 주세요.']
  const actualDate = new Date(room.startedAt).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul', dateStyle: 'long', timeStyle: 'short' })
  return <main className="report-page">
    <nav className="report-brand" aria-label="서비스"><span>RE:ACT<span className="report-brand-dot">.</span></span><span>LECTURE REPORT</span></nav>
    <header className="report-header"><div><span className="report-eyebrow">강연 회고 보고서</span><h1>{room.title}</h1>
      <p>{actualDate} (한국 시간) <span aria-hidden="true">/</span> 강연 시간 {elapsed(room.endedAt, room.startedAt)}</p></div>
      <span className="report-status-pill">{state.status === 'COMPLETED' ? '보고서 완료' : '강연 회고'}</span>
    </header>
    {state.error && <div className="report-error" role="alert"><p>{state.error}</p><button onClick={state.check} disabled={state.busy}>상태 확인</button></div>}
    {state.status === 'COMPLETED' && state.content ? <ReportView content={state.content} room={room} /> :
      <section className="report-state" aria-live="polite" aria-busy={state.busy}>
        <span className="report-state-symbol" aria-hidden="true">{state.status === 'FAILED' ? '!' : '◎'}</span>
        <h2>{state.error ? '상태 확인이 필요합니다' : message[0]}</h2>
        {!state.error && <p>{message[1]}</p>}
        {!state.error && ['NOT_CREATED', 'LIVE', 'NOT_READY'].includes(state.status) && <button onClick={state.create} disabled={state.busy || state.status !== 'NOT_CREATED'}>보고서 생성</button>}
        {!state.error && state.status === 'NOT_READY' && <button className="report-secondary" onClick={state.check} disabled={state.busy}>상태 확인</button>}
      </section>}
    <footer className="report-footer">RE:ACT · 청중의 반응을 다음 강연의 시작으로</footer>
  </main>
}
