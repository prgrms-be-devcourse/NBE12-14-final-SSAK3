import React, { useState } from 'react'
import { ChatOriginal, SpeechOriginal } from './ReportOriginal.jsx'

const statusText = {
  NO_QUESTIONS: '청중 기록에서 후속 검토할 질문을 찾지 못했습니다.',
  ALL_ANSWERED: '청중 질문에 대한 답변이 강사 발언에서 확인되었습니다.',
  UNAVAILABLE: '근거 기록을 확인하기 어려워 후속 답변 후보를 표시할 수 없습니다.',
}

function Candidate({ group, findRecord }) {
  const isDraft = group.kind === 'DRAFT'
  const hasRepeat = group.questions.some(question => question.requests.some(request => request.reexplanation))
  return <article className="report-follow-up-card">
    <header>
      <h3>{group.title}</h3>
      <span className="report-muted">답변 확인 필요{hasRepeat && ' · 재설명 요청'}</span>
    </header>
    <h4>{isDraft ? '보충 설명 초안' : '준비 방향'}</h4>
    <p className="report-follow-up-text">{group.text}</p>
    <div className="report-follow-up-sources">
      <details className="report-evidence">
        <summary>청중 질문 원문 <span>질문 {group.questions.length}개</span></summary>
        {group.questions.map((question, index) => <div key={index} className="report-evidence-record">
          {question.requests.map(request => {
            const message = findRecord('CHAT_MESSAGE', request.messageId)
            return <div key={request.messageId + ':' + request.quote} className="report-follow-up-request">
              {request.reexplanation && <strong>재설명 요청</strong>}
              {message ? <ChatOriginal message={message} quote={request.quote} />
                : <p>연결된 질문 원문을 확인할 수 없습니다.</p>}
            </div>
          })}
        </div>)}
      </details>
      {isDraft && <details className="report-evidence">
        <summary>참고한 강사 발언 <span>{group.segmentIds.length}건</span></summary>
        {group.segmentIds.map(id => {
          const segment = findRecord('STT_SEGMENT', id)
          return <div className="report-evidence-record" key={id}>
            {segment ? <SpeechOriginal segment={segment} /> : <p>연결된 발언 원문을 확인할 수 없습니다.</p>}
          </div>
        })}
      </details>}
    </div>
  </article>
}

export function ReportFollowUp({ followUp, findRecord }) {
  const [showAll, setShowAll] = useState(false)
  const { status, groups } = followUp
  const hasCandidates = status === 'CANDIDATES'
  return <section className="report-follow-up" aria-labelledby="report-follow-up-title">
    <header className="report-section-heading">
      <h2 id="report-follow-up-title" tabIndex={-1}>후속 답변 후보</h2>
      {hasCandidates && <span className="report-muted">총 {groups.length}묶음</span>}
    </header>
    {hasCandidates ? <>
      <p className="report-muted">저장된 발언에서 답변을 확인하기 어려운 질문을 모았습니다. 보충 설명 초안과 준비 방향은 강사가 검토할 AI 제안입니다.</p>
      {(showAll ? groups : groups.slice(0, 3)).map((group, i) => <Candidate key={i} group={group} findRecord={findRecord} />)}
      {!showAll && groups.length > 3 && <button type="button" className="report-secondary"
        onClick={() => setShowAll(true)}>더 보기 · 남은 {groups.length - 3}묶음</button>}
    </> : <p className={status === 'UNAVAILABLE' ? 'report-limits' : 'report-empty'}>
      {statusText[status] ?? statusText.UNAVAILABLE}
    </p>}
  </section>
}
