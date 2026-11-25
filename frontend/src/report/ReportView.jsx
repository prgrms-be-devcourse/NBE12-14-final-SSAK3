import React, { useEffect, useRef, useState } from 'react'
import { Icon } from '../ui/Icon.jsx'
import { ChatOriginal, ClockTime, SpeechOriginal } from './ReportOriginal.jsx'
import { ReportFollowUp } from './ReportFollowUp.jsx'

const groups = [['mainInterests', 'message', '주요 관심사'],
  ['reviewPoints', 'activity', '보충 설명 검토 지점'],
  ['suggestions', 'arrow-up-right', '다음 강연을 위한 제안']]
const labels = { CHAT_MESSAGE: '청중 질문·의견', STT_SEGMENT: '강사 발언', POLL_RESULT: '투표·이해도 체크' }
const choices = { UNDERSTOOD: '이해됨', CONFUSED: '헷갈림', NOT_UNDERSTOOD: '모르겠음' }
export function elapsed(value, start) {
  const seconds = Math.floor((Date.parse(value) - Date.parse(start)) / 1000)
  if (!Number.isFinite(seconds) || seconds < 0) return '시각 확인 필요'
  return `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`
}
const keyOf = (type, id) => `${type}:${id}`
const targetId = (index, type, id) => `report-record-${index}-${type}-${id}`
function records(row) {
  return [
    ...(row.sttSegments || []).map(r => ({ type: 'STT_SEGMENT', id: r.segmentId, time: r.startAt, data: r })),
    ...(row.chatMessages || []).map(r => ({ type: 'CHAT_MESSAGE', id: r.messageId, time: r.createdAt, data: r })),
    ...[...(row.understandingChecks || []), ...(row.polls || [])].map(r => ({ type: 'POLL_RESULT', id: r.pollId, time: r.openedAt, data: r })),
  ]
}
function Limits({ values = [] }) {
  return values.length > 0 && <aside className="report-limits" aria-label="관련 데이터 한계">
    <strong>데이터 한계</strong><ul>{values.map((s, i) => <li key={i}>{s}</li>)}</ul>
  </aside>
}
function GapNotice({ gap }) {
  const start = gap.startedAt ?? gap.startAt
  const end = gap.endedAt ?? gap.endAt
  return <p className="report-limits">발언 기록 없음 · 누락 시작 <ClockTime value={start} />
    {' · '}{end ? <>복구 <ClockTime value={end} /></> : '복구 시각 미확인'} (한국 시간).
    누락된 강사 설명·답변은 확인할 수 없습니다.</p>
}
function pollOptions(poll) {
  // Older reports identify choices by their label rather than their option ID.
  return poll.options ?? Object.entries(poll.counts || {}).map(([label, count]) => ({
    content: choices[label] || label, count,
  }))
}
function UnderstandingResults({ item, recordIndex }) {
  return item.evidence.filter(ref => ref.type === 'POLL_RESULT').map(ref => {
    const poll = recordIndex.get(keyOf(ref.type, ref.id))?.[0]?.record.data
    if (poll?.type !== 'UNDERSTANDING') return null
    const options = pollOptions(poll)
    if (options.filter(option => option.count > 0).length < 2) return null
    return <section key={ref.id} className="report-understanding" aria-label="이해도 체크 최종 결과">
      <strong>{poll.question}</strong>
      <p>시작 <ClockTime value={poll.openedAt} /> · 마감 <ClockTime value={poll.closedAt} /> (한국 시간)</p>
      <p>{options.map(option => `${option.content} ${option.count}명`).join(' · ')} · 미응답 {poll.unrespondedParticipants}명</p>
    </section>
  })
}
function RecordBody({ record, start, optionIds = [] }) {
  const r = record.data
  if (record.type === 'STT_SEGMENT') return <SpeechOriginal segment={r} />
  if (record.type === 'CHAT_MESSAGE') return <ChatOriginal message={r} />
  return <div className="report-poll">
    <p><strong>{r.question}</strong></p>
    <p className="report-muted">시작 {elapsed(r.openedAt, start)} · 마감 {elapsed(r.closedAt, start)} · 마감된 최종 결과</p>
    <p>전체 {r.totalParticipants ?? '확인 필요'}명 · 응답 {r.respondedParticipants ?? '확인 필요'}명 · 미응답 {r.unrespondedParticipants ?? '확인 필요'}명</p>
    <ul>{pollOptions(r).map(option => {
      const selected = optionIds?.includes(option.optionId)
      return <li key={option.optionId ?? option.content} className={selected ? 'report-option-selected' : undefined}>
        <span>{option.content}{selected && <small>이 입장의 근거</small>}</span>
        <strong>{option.count}명</strong>
      </li>
    })}</ul>
    <small>미응답은 모르겠음으로 집계하지 않습니다. 시작 구간에 연결된 최종 결과이며 해당 분의 실시간 상태가 아닙니다.</small>
  </div>
}

function Evidence({ item, recordIndex, timeline, room, jump }) {
    return <details className="report-evidence"><summary>근거 보기 <span>{item.evidence.length}건</span></summary>
      <div className="report-evidence-list">{item.evidence.map(ref => {
        const matches = recordIndex.get(keyOf(ref.type, ref.id)) || []
        // A segment can overlap multiple windows. Keep each valid destination available.
        const related = matches.filter(m => item.relatedWindows.some(w => Date.parse(w.startAt) === Date.parse(timeline[m.index].startAt)))
        const found = related[0]
        return <div className="report-evidence-record" key={keyOf(ref.type, ref.id)}>
          <span className="report-tag">실제 기록 · {labels[ref.type] || ref.type}</span>
          {found ? <><RecordBody record={found.record} start={room.startedAt} optionIds={ref.optionIds} />
            {related.map(({ record, index }) => <button className="report-text-button" key={index}
              onClick={() => jump(index, record, ref.optionIds)}>해당 시간대 기록 보기 · {elapsed(timeline[index].startAt, room.startedAt)} ↗</button>)}
          </> : <p>연결된 근거 기록을 확인할 수 없습니다.</p>}
        </div>
      })}</div>
    </details>
  }

export function ReportView({ content, room }) {
  const [opened, setOpened] = useState(new Set())
  const [highlight, setHighlight] = useState(null)
  const highlightTimer = useRef(null)
  const timeline = content.timeline || []
  const recordIndex = new Map()
  timeline.forEach((row, index) => records(row).forEach(record => {
    const key = keyOf(record.type, record.id)
    const list = recordIndex.get(key) || []
    list.push({ record, index }); recordIndex.set(key, list)
  }))
  useEffect(() => () => clearTimeout(highlightTimer.current), [])
  useEffect(() => {
    if (!highlight) return
    const node = document.getElementById(highlight.id)
    node?.scrollIntoView({ behavior: 'auto', block: 'center' })
    node?.focus({ preventScroll: true })
    clearTimeout(highlightTimer.current)
    highlightTimer.current = setTimeout(() => setHighlight(null), 4000)
  }, [highlight])
  function jump(index, record, optionIds) {
    setOpened(previous => new Set([...previous, index]))
    const id = targetId(index, record.type, record.id)
    setHighlight({ id, optionIds })
  }
  const hasRecords = timeline.some(row => records(row).length || row.sttGaps?.length || row.audienceOpinions?.length)
  return <>
    <nav className="report-contents" aria-label="보고서 목차">
      <a href="#report-overview-title"><Icon name="chart" size={16} />강연 요약</a>
      {content.followUp && <a href="#report-follow-up-title"><Icon name="message" size={16} />후속 답변</a>}
      <a href="#report-timeline-title"><Icon name="clock" size={16} />시간대별 기록</a>
    </nav>
    {content.dataLimitations?.length > 0 && <details className="report-global-limit">
      <summary>데이터 한계 안내 <span>{content.dataLimitations.length}건 · 펼쳐 보기</span></summary>
      <ul>{content.dataLimitations.map((s, i) => <li key={i}>{s}</li>)}</ul>
    </details>}
    <section aria-labelledby="report-overview-title">
      <div className="report-section-heading"><h2 id="report-overview-title" tabIndex={-1}>강연을 돌아보는 세 가지 관점</h2><span className="report-tag report-ai">AI 요약 · 검토용</span></div>
      {content.evidencePolicy && <p className="report-muted">{content.evidencePolicy}</p>}
      <div className="report-overview">{groups.map(([key, icon, title]) => <section className="report-column" key={key} aria-label={title}>
        <header><span className="report-column-icon"><Icon name={icon} size={18} /></span><h3>{title}</h3></header>
        {!content.overview[key]?.length && <p className="report-empty">이 항목에 표시할 요약이 없습니다.</p>}
        {(content.overview[key] || []).map((item, i) => <article className="report-card" key={i}>
          <p className="report-card-text">{item.text}</p>
          {key === 'reviewPoints' && !item.perspectives?.length && <UnderstandingResults item={item} recordIndex={recordIndex} />}
          <Limits values={item.limitations} />
          {item.perspectives?.length ? <div>{item.perspectives.map((perspective, n) => <section className="report-perspective" key={n}>
            <strong>의견 {n + 1}</strong><p>{perspective.text}</p><Limits values={perspective.limitations} /><Evidence item={perspective} recordIndex={recordIndex} timeline={timeline} room={room} jump={jump} />
          </section>)}</div> : <Evidence item={item} recordIndex={recordIndex} timeline={timeline} room={room} jump={jump} />}
        </article>)}
      </section>)}</div>
    </section>
    {content.followUp && <ReportFollowUp followUp={content.followUp}
      findRecord={(type, id) => recordIndex.get(keyOf(type, id))?.[0]?.record.data} />}
    <section className="report-timeline" aria-labelledby="report-timeline-title">
      <div className="report-section-heading"><h2 id="report-timeline-title" tabIndex={-1}>시간대별 상세 기록</h2><span className="report-muted">강연 시작 기준 · 1분 단위</span></div>
      {!hasRecords ? <p className="report-empty">표시할 시간대별 기록이 없습니다.</p> : timeline.map((row, index) => {
        const rows = records(row)
        const count = rows.length
        const gaps = row.sttGaps || [], opinions = row.audienceOpinions || []
        return <details className="report-window" key={row.startAt} open={opened.has(index)} onToggle={event => {
          const isOpen = event.currentTarget.open
          setOpened(prev => { if (prev.has(index) === isOpen) return prev; const next = new Set(prev); isOpen ? next.add(index) : next.delete(index); return next })
        }}>
          <summary><span className="report-window-time">{elapsed(row.startAt, room.startedAt)}
            {Date.parse(row.startAt) !== Date.parse(row.endAt) && `—${elapsed(row.endAt, room.startedAt)}`}</span>
            <span className="report-window-count">{count ? `실제 기록 ${count}건` : '실제 기록 없음'}</span>
            {(gaps.length > 0 || opinions.some(o => o.status === 'FAILED')) && <span className="report-warning-tag">누락·실패 안내</span>}
            {opinions.some(o => o.status !== 'FAILED') && <span className="report-tag report-ai">AI 요약</span>}
          </summary>
          <div className="report-window-body">
            {gaps.map(gap => <GapNotice key={gap.gapId ?? gap.id} gap={gap} />)}
            {opinions.filter(o => o.status === 'FAILED').map((o, n) => <p className="report-limits" key={n}>청중 의견 AI 분석 실패 · {elapsed(o.startAt, room.startedAt)}~{elapsed(o.endAt, room.startedAt)}. 남아 있는 원문 기록을 확인해 주세요.</p>)}
            {Object.entries(labels).map(([type, label]) => <section className="report-record-group" key={type}>
              <h4>{label} <span className="report-tag">실제 기록</span></h4>
              {!rows.some(r => r.type === type) && <p className="report-muted">이 구간에 해당 기록이 없습니다.</p>}
              {rows.filter(r => r.type === type).sort((a, b) => Date.parse(a.time) - Date.parse(b.time) || a.id - b.id).map(record => {
                const id = targetId(index, record.type, record.id)
                const active = highlight?.id === id
                return <article tabIndex={-1} id={id} key={id} className={`report-record ${active ? 'report-highlight' : ''}`}>
                  {record.type !== 'STT_SEGMENT' && <time>{elapsed(record.time, room.startedAt)}</time>}
                  <RecordBody record={record} start={room.startedAt} optionIds={active ? highlight.optionIds : undefined} />
                </article>
              })}
            </section>)}
            <section className="report-record-group"><h4>청중 의견 AI 요약 <span className="report-tag report-ai">AI 생성</span></h4>
              {!opinions.some(o => o.status !== 'FAILED') && <p className="report-muted">표시할 AI 요약이 없습니다.</p>}
              {opinions.filter(o => o.status !== 'FAILED').map((o, n) => <article className="report-record" key={n}><strong>{o.topic}</strong><p>{o.summary}</p></article>)}
            </section>
          </div>
        </details>
      })}
    </section>
  </>
}
