import React, { useEffect, useState } from 'react'
import { recentClosedLimit } from './pollResults.js'
import { watchPollResults } from './watchPollResults.js'
import './pollResults.css'

const percent = value => `${value.toFixed(1)}%`

export function PollResults({ roomId }) {
  const [polls, setPolls] = useState([])
  const [connected, setConnected] = useState(false)
  const [denied, setDenied] = useState(false)

  useEffect(() => {
    const subscription = watchPollResults({
      roomId, type: 'GENERAL', closedLimit: recentClosedLimit, onPolls: setPolls, onConnection: setConnected, onDenied: () => setDenied(true),
    })
    return () => subscription.close()
  }, [roomId])

  if (denied || polls.length === 0) return null
  const live = polls.some(poll => poll.status === 'OPEN')
  return <section className="poll-results" aria-label="투표 현황">
    <div className="poll-results-heading">
      <h2>투표 현황</h2>
      {live && !connected && <span>연결 확인 중</span>}
    </div>
    <ul className="poll-results-list">
      {polls.map(poll => <PollResultCard key={poll.pollId} poll={poll} />)}
    </ul>
  </section>
}

function PollResultCard({ poll }) {
  const open = poll.status === 'OPEN'
  return <li className="poll-results-card" aria-label={poll.question}>
    <div className="poll-results-title">
      <strong>{poll.question}</strong>
      <span className={open ? 'poll-results-open' : undefined}>{open ? '진행 중' : '마감'}</span>
    </div>
    <ul className="poll-results-options">
      {poll.options.map(option => <li key={option.optionId}>
        <div className="poll-results-row">
          <span>{option.content}</span>
          <span>{option.count}명</span>
        </div>
        <div className="poll-results-bar" aria-hidden="true">
          <span style={{ width: `${option.percentageOfResponses}%` }} />
        </div>
        <p>응답자의 {percent(option.percentageOfResponses)} · 전체의 {percent(option.percentageOfAllParticipants)}</p>
      </li>)}
    </ul>
    <p className="poll-results-totals">
      전체 참여 {poll.totalParticipants}명 · 응답 {poll.respondedParticipants}명({percent(poll.responseRate)}) · 미응답 {poll.unrespondedParticipants}명
    </p>
  </li>
}
