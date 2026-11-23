import React, { useEffect, useId, useState } from 'react'
import { watchUnderstandingSummary } from './watchUnderstandingSummary.js'
import './understandingSummary.css'

const percent = value => `${value.toFixed(1)}%`

export function UnderstandingSummary({ roomId }) {
  const [results, setResults] = useState(null)
  const [connected, setConnected] = useState(false)
  const [denied, setDenied] = useState(false)
  const headingId = useId()

  useEffect(() => {
    const subscription = watchUnderstandingSummary({
      roomId, onResults: setResults, onConnection: setConnected, onDenied: () => setDenied(true),
    })
    return () => subscription.close()
  }, [roomId])

  if (denied || !results) return null
  const open = results.status === 'OPEN'
  return <section className="understanding-summary" aria-labelledby={headingId}>
    <div className="understanding-summary-heading">
      <h2 id={headingId}>이해도 체크 결과</h2>
      <span className={open ? 'understanding-summary-open' : undefined}>{open ? '진행 중' : '마감'}</span>
      {open && !connected && <span>연결 확인 중</span>}
    </div>
    <ul className="understanding-summary-options">
      {results.options.map(option => <li key={option.optionId}>
        <div className="understanding-summary-row">
          <strong>{option.content}</strong>
          <span>{option.count}명</span>
        </div>
        <div className="understanding-summary-bar" aria-hidden="true">
          <span style={{ width: `${option.percentageOfResponses}%` }} />
        </div>
        <p>응답자의 {percent(option.percentageOfResponses)} · 전체의 {percent(option.percentageOfAllParticipants)}</p>
      </li>)}
    </ul>
    <dl className="understanding-summary-totals">
      <div><dt>전체 참여</dt><dd>{results.totalParticipants}명</dd></div>
      <div><dt>응답</dt><dd>{results.respondedParticipants}명 ({percent(results.responseRate)})</dd></div>
      <div><dt>미응답</dt><dd>{results.unrespondedParticipants}명</dd></div>
    </dl>
  </section>
}
