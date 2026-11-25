import React, { useEffect, useId, useState } from 'react'
import { understandingApi } from './understandingApi.js'
import { watchPollResults } from './watchPollResults.js'
import { bars, describe, formatRatio, plot, roundLabel, roundTime, statusLabels, thresholdY } from './understandingRounds.js'
import './understandingRounds.css'

const topPad = 24
const width = 560
const height = topPad + plot.height + 52

// 마감된 이해도 체크를 라운드별 막대로 비교한다. 새 체크가 마감될 때만 서버에서 다시 받는다.
export function UnderstandingRounds({ roomId }) {
  const [closedCount, setClosedCount] = useState(null)
  const [denied, setDenied] = useState(false)
  const [data, setData] = useState(null)
  const [failed, setFailed] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const headingId = useId()

  useEffect(() => {
    const subscription = watchPollResults({
      roomId, type: 'UNDERSTANDING',
      onPolls: polls => setClosedCount(polls.filter(poll => poll.status === 'CLOSED').length),
      onDenied: () => setDenied(true),
    })
    return () => subscription.close()
  }, [roomId])

  useEffect(() => {
    if (!closedCount) return undefined
    let current = true
    understandingApi(roomId).rounds()
      .then(result => { if (current) { setData(result); setFailed(false) } })
      .catch(() => { if (current) setFailed(true) })
    return () => { current = false }
  }, [roomId, closedCount, attempt])

  if (denied || !closedCount) return null
  const rounds = data?.rounds ?? []
  if (!failed && rounds.length === 0) return null

  return <section className="understanding-rounds" aria-labelledby={headingId}>
    <div className="understanding-rounds-heading">
      <h2 id={headingId}>이해도 변화</h2>
      <span>마감된 {rounds.length}개 라운드</span>
    </div>
    {failed && <p className="understanding-rounds-error" role="alert">
      {rounds.length ? '최신 결과를 불러오지 못해 마지막으로 받은 내용을 보여 드려요.' : '라운드별 변화를 불러오지 못했어요.'}
      <button type="button" onClick={() => setAttempt(count => count + 1)}>다시 시도</button>
    </p>}
    {rounds.length > 0 && <RoundsChart data={data} />}
  </section>
}

function RoundsChart({ data }) {
  const { rounds, ratio, minCount } = data
  const shapes = bars(rounds)
  const line = topPad + thresholdY(ratio)
  return <>
    <p className="understanding-rounds-note">
      막대 높이는 응답한 사람을 100%로 본 비율이에요. 응답하지 않은 사람은 ‘모르겠음’으로 세지 않아요.
    </p>
    <svg className="understanding-rounds-chart" viewBox={`0 0 ${width} ${height}`} role="img" aria-label={describe(rounds, ratio)}>
      <g className="understanding-rounds-grid">
        {[0, 0.5, 1].map(step => <line key={step} x1={plot.left} x2={plot.right} y1={topPad + plot.height * step} y2={topPad + plot.height * step} />)}
      </g>
      <g className="understanding-rounds-axis" textAnchor="end">
        {[['100%', 0], ['50%', 0.5], ['0%', 1]].map(([label, step]) =>
          <text key={label} x={plot.left - 6} y={topPad + plot.height * step + 4}>{label}</text>)}
      </g>
      {shapes.map((bar, index) => <g key={rounds[index].pollId}>
        {bar.segments.map(segment => <rect key={segment.status} className={`understanding-rounds-${segment.status.toLowerCase()}`}
          x={bar.x} y={topPad + segment.y} width={bar.width} height={segment.height} />)}
        {rounds[index].lowUnderstanding && <g className="understanding-rounds-flag">
          <rect x={bar.centerX - 26} y={topPad - 22} width="52" height="16" rx="8" />
          <text x={bar.centerX} y={topPad - 10.5} textAnchor="middle">이해 부족</text>
        </g>}
        <text className="understanding-rounds-label" x={bar.centerX} y={topPad + plot.height + 20} textAnchor="middle">{roundLabel(index)}</text>
        <text className="understanding-rounds-time" x={bar.centerX} y={topPad + plot.height + 35} textAnchor="middle">{roundTime(rounds[index])}</text>
      </g>)}
      <line className="understanding-rounds-threshold" x1={plot.left} x2={plot.right} y1={line} y2={line} />
    </svg>
    <ul className="understanding-rounds-legend">
      {Object.entries(statusLabels).map(([status, label]) => <li key={status}>
        <span className={`understanding-rounds-swatch understanding-rounds-${status.toLowerCase()}`} aria-hidden="true" />{label}
      </li>)}
      <li><span className="understanding-rounds-dash" aria-hidden="true" />이해 부족 기준 {ratio}%</li>
    </ul>
    <div className="understanding-rounds-table">
      <table>
        <caption>헷갈림과 모르겠음이 {minCount}명 이상이고 응답한 사람의 {ratio}% 이상이면 ‘이해 부족’으로 표시해요.</caption>
        <thead>
          <tr>
            <th scope="col">라운드</th>
            <th scope="col">응답</th>
            <th scope="col">{statusLabels.UNDERSTOOD}</th>
            <th scope="col">{statusLabels.CONFUSED}</th>
            <th scope="col">{statusLabels.NOT_UNDERSTOOD}</th>
            <th scope="col">부족 비율</th>
            <th scope="col">표시</th>
          </tr>
        </thead>
        <tbody>
          {rounds.map((round, index) => <tr key={round.pollId}>
            <th scope="row">{roundLabel(index)} <time dateTime={round.openedAt}>{roundTime(round)}</time></th>
            <td>{round.respondedParticipants}/{round.totalParticipants}명</td>
            <td>{round.counts.UNDERSTOOD}</td>
            <td>{round.counts.CONFUSED}</td>
            <td>{round.counts.NOT_UNDERSTOOD}</td>
            <td>{formatRatio(round.lackRatio)}</td>
            <td>{round.lowUnderstanding && <span className="understanding-rounds-badge">이해 부족</span>}</td>
          </tr>)}
        </tbody>
      </table>
    </div>
    <p className="understanding-rounds-note">그 시점에 관찰한 수치이며, 설명이 잘못됐다는 뜻은 아니에요.</p>
  </>
}
