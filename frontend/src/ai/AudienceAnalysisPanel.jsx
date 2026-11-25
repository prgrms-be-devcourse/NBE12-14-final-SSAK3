import React, { useState } from 'react'
import { Icon } from '../ui/Icon.jsx'

const categoryNames = { QUESTION: '질문', OPINION: '의견', REQUEST: '요청·제안', CONFUSION: '이해 부족 표현', MIXED: '혼합' }
const formatTime = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', hour12: false,
  year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' })
const clockTime = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', hour12: false,
  hour: '2-digit', minute: '2-digit', second: '2-digit' })
const timestamp = value => value ? formatTime.format(new Date(value)) : '—'
const interval = analysis => `${timestamp(analysis.windowStartAt)} ~ ${clockTime.format(new Date(analysis.windowEndAt))}`

function statusText(analysis) {
  if (!analysis) return '아직 분석 기록이 없습니다.'
  if (analysis.status === 'PENDING') return '새 청중 의견 분석을 기다리고 있습니다.'
  if (analysis.status === 'PROCESSING') return '새 청중 의견을 분석하고 있습니다.'
  if (analysis.status === 'FAILED') return '이 구간의 의견을 요약하지 못했습니다.'
  return analysis.topicGroups.length ? '이 구간의 분석이 완료되었습니다.' : '이 구간에는 표시할 새 요약이 없습니다.'
}

export function AudienceAnalysisPanel({ state, actions }) {
  const [historyOpen, setHistoryOpen] = useState(false)
  const latest = state.analyses[0]
  const pending = state.candidate && state.candidate.analysisId !== state.current?.analysisId
  const history = state.visibleAnalyses.filter(a => a.analysisId !== state.current?.analysisId)
  return <section className="ai-panel" aria-label="AI 질문·의견 분석">
    <div className="ai-panel-heading"><div><h2><span className="ai-heading-icon"><Icon name="activity" size={18} /></span>AI 질문·의견 분석</h2>
      <p>AI가 정리한 내용입니다. 판단하기 전에 실제 원문을 확인해 주세요.</p></div>
      <span className={`ai-live-label${state.error ? ' ai-stopped' : ''}`}>{state.error ? '자동 갱신 중단' : '5초마다 결과 확인'}</span></div>
    <div className="ai-meta">모든 시각은 한국 시간(KST) 기준입니다.
      {state.checkedAt && <span>마지막 조회 · {timestamp(state.checkedAt)}</span>}</div>
    {state.error && <div className="room-warning" role="alert">
      <strong>최신 분석 결과를 확인하지 못했습니다.</strong>
      {state.loaded && <p>마지막으로 불러온 내용을 표시하고 있습니다.</p>}
      <button onClick={actions.reconnect} disabled={state.busy}>{state.busy ? '연결 중…' : '다시 연결'}</button>
    </div>}
    {!state.loaded && state.busy && <p role="status" className="ai-empty">분석 결과를 불러오고 있습니다.</p>}
    {state.loaded && <>
      <div className="ai-status" role="status">
        {latest && <span>최근 분석 구간 · {interval(latest)}</span>}
        <p>{statusText(latest)}</p>
      </div>
      {pending && <div className="ai-new" role="status"><span>새 요약이 있습니다. 읽던 원문을 유지하고 있습니다.</span>
        <button onClick={actions.showLatest}>새 요약 보기</button></div>}
      {state.current ? <AnalysisGroup analysis={state.current} state={state} actions={actions}
        label={latest?.analysisId === state.current.analysisId ? '최신 요약' : '마지막 요약'} />
        : <p className="ai-empty">최근 조회 범위에 표시할 요약이 없습니다.</p>}
      <div className="ai-history">
        <button className="room-secondary" aria-expanded={historyOpen} aria-controls="ai-history-content"
          onClick={() => {
            if (historyOpen) actions.closeSources(history.flatMap(a => a.topicGroups.map(t => t.topicGroupId)))
            setHistoryOpen(value => !value)
          }}>{historyOpen ? '이전 분석 접기' : '이전 분석 보기'}</button>
        <div id="ai-history-content" hidden={!historyOpen}>
          <p className="ai-meta">최근 {state.limit}개 분석 구간 범위 · 현재 표시 중인 요약 구간은 제외됩니다.</p>
          {history.length ? history.map(analysis => <AnalysisGroup key={analysis.analysisId}
            analysis={analysis} state={state} actions={actions} label="이전 분석" />)
            : <p className="ai-empty">조회 범위에 이전 분석이 없습니다.</p>}
          {state.limit < 50 ? <button className="room-secondary" disabled={state.busy || state.error}
            onClick={actions.more}>{state.busy ? '조회 중…' : '더 보기'}</button>
            : <p className="ai-meta">최근 50개 구간까지 조회합니다. 그보다 오래된 기록은 이 화면에서 제공하지 않습니다.</p>}
        </div>
      </div>
    </>}
  </section>
}

function AnalysisGroup({ analysis, state, actions, label }) {
  return <section className="ai-group" aria-label={`${label} ${analysis.analysisId}`}>
    <div className="ai-group-heading"><h3>{label}</h3><span>{interval(analysis)}</span></div>
    {analysis.status === 'COMPLETED' && analysis.topicGroups.length
      ? analysis.topicGroups.map(topic => <TopicCard key={topic.topicGroupId} topic={topic} state={state} actions={actions} />)
      : <p className="ai-empty">{statusText(analysis)}</p>}
  </section>
}

function TopicCard({ topic, state, actions }) {
  const id = topic.topicGroupId, open = state.openIds.includes(id), source = state.sources[id]
  return <article className="ai-topic" aria-label={topic.topic}>
    <div className="ai-topic-meta"><span className="ai-category">{categoryNames[topic.category] || '분류 확인 필요'}</span><span>관련 원문 {topic.messageCount}건</span></div>
    <h4>{topic.topic}</h4>
    <p className="ai-summary"><strong>AI 요약</strong>{topic.summary}</p>
    <button className="room-secondary" aria-expanded={open} aria-controls={`ai-source-${id}`}
      onClick={() => actions.toggleSource(id)}>{open ? '원문 접기' : '원문 보기'}</button>
    {open && <div className="ai-sources" id={`ai-source-${id}`}>
      <h5>근거 원문 · 실제 청중 채팅</h5>
      {(!source || source.status === 'loading') && <p role="status">원문을 불러오고 있습니다.</p>}
      {source?.status === 'missing' && <p role="alert">근거 원문을 찾을 수 없습니다.</p>}
      {source?.status === 'error' && <div role="alert"><p>원문을 불러오지 못했습니다.</p>
        <button className="room-secondary" onClick={() => actions.retrySource(id)}>다시 시도</button></div>}
      {source?.status === 'ready' && (source.messages.length ? <ul>{source.messages.map(message =>
        <li key={message.messageId}><div><strong>{message.nickname}</strong><time dateTime={message.receivedAt}>{timestamp(message.receivedAt)}</time></div>
          <p>{message.content}</p></li>)}</ul> : <p>표시할 근거 원문이 없습니다.</p>)}
    </div>}
  </article>
}
