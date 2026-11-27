import React, {
  useEffect,
  useId,
  useState,
} from 'react'

import { understandingApi } from './understandingApi.js'
import { watchPollResults } from './watchPollResults.js'
import { percentage } from './understandingSummary.js'

import {
  barSegments,
  formatRatio,
  mergeClosedRounds,
  roundLabel,
  roundTime,
  shares,
  statusLabels,
  statusOrder,
} from './understandingRounds.js'

import './understandingRounds.css'

export function UnderstandingRounds({ roomId }) {
  const [polls, setPolls] = useState([])
  const [denied, setDenied] = useState(false)
  const [data, setData] = useState(null)
  const [failed, setFailed] = useState(false)
  const [attempt, setAttempt] = useState(0)

  const headingId = useId()

  useEffect(() => {
    setPolls([])
    setDenied(false)
    setData(null)
    setFailed(false)

    const subscription = watchPollResults({
      roomId,
      type: 'UNDERSTANDING',
      onPolls: setPolls,
      onDenied: () => setDenied(true),
    })

    return () => subscription.close()
  }, [roomId])

  const closedKey = polls
      .filter(poll => poll.status === 'CLOSED')
      .map(poll => poll.pollId)
      .join(',')

  useEffect(() => {
    if (!closedKey) return undefined

    let current = true

    setFailed(false)

    understandingApi(roomId).rounds()
        .then(result => {
          if (current) {
            setData({
              roomId,
              result,
            })

            setFailed(false)
          }
        })
        .catch(() => {
          if (current) {
            setFailed(true)
          }
        })

    return () => {
      current = false
    }
  }, [roomId, closedKey, attempt])

  const serverData =
      data?.roomId === roomId
          ? data.result
          : null

  const rounds = mergeClosedRounds(
      polls,
      serverData?.rounds,
  )

  if (denied || rounds.length === 0) {
    return null
  }

  return (
      <section
          className="understanding-rounds"
          aria-labelledby={headingId}
      >

        <div className="understanding-rounds-heading">

          <h2 id={headingId}>
            회차별 이해도 결과
          </h2>

          <span>
                    마감된 {rounds.length}회
                </span>

        </div>

        <p className="understanding-rounds-note">
          각 회차의 응답자 기준 이해도를 비교할 수 있습니다.
        </p>

        {failed && (
            <div
                className="understanding-rounds-error"
                role="alert"
            >
              <p>
                최신 이해 부족 판정 정보를 불러오지 못했습니다.
                확인된 응답 결과를 표시합니다.
              </p>

              <button
                  type="button"
                  onClick={() =>
                      setAttempt(count => count + 1)
                  }
              >
                다시 시도
              </button>
            </div>
        )}

        <RoundsLegend />

        <ol
            className="understanding-rounds-list"
            aria-label="마감된 이해도 체크 목록"
        >
          {rounds.map((round, index) => (
              <RoundCard
                  key={round.pollId}
                  round={round}
                  index={index}
                  failed={failed}
              />
          ))}
        </ol>

        {Number.isFinite(serverData?.ratio) &&
            Number.isFinite(serverData?.minCount) && (
                <p className="understanding-rounds-note">
                  헷갈림과 모르겠음이 {serverData.minCount}명 이상이고,
                  응답자의 {serverData.ratio}% 이상이면
                  이해 부족으로 표시합니다.
                </p>
            )}

        <p className="understanding-rounds-note">
          미응답자는 이해도 비율에 포함하지 않습니다.
        </p>

      </section>
  )
}

function RoundsLegend() {
  return (
      <ul className="understanding-rounds-legend">

        {statusOrder.map(status => (
            <li key={status}>

                    <span
                        className={
                            `understanding-rounds-swatch ` +
                            `understanding-rounds-${status.toLowerCase()}`
                        }
                        aria-hidden="true"
                    />

              {statusLabels[status]}

            </li>
        ))}

      </ul>
  )
}

function RoundCard({ round, index, failed }) {
  const segments = barSegments(round)
  const values = shares(round)

  const responded = round.respondedParticipants
  const total = round.totalParticipants

  const hasResponses = responded > 0

  const responseRate = percentage(
      responded,
      total,
  )

  const unanswered = Math.max(
      0,
      total - responded,
  )

  const description = hasResponses
      ? statusOrder.map(status =>
          `${statusLabels[status]} ${formatRatio(values[status])}`,
      ).join(', ')
      : '응답 없음'

  return (
      <li
          className="understanding-rounds-card"
          aria-label={roundLabel(index)}
      >

        <div className="understanding-rounds-card-head">

          <div className="understanding-rounds-card-title">

            <strong>
              {roundLabel(index)}
            </strong>

            <time dateTime={round.openedAt}>
              {roundTime(round)}
            </time>

            {round.lowUnderstanding === true && (
                <span className="understanding-rounds-badge">
                            이해 부족
                        </span>
            )}

          </div>

          <span className="understanding-rounds-response">
                    {responded}/{total}명 응답
                </span>

        </div>

        {hasResponses ? (
            <div
                className="understanding-rounds-bar"
                role="img"
                aria-label={description}
            >

              {segments.map(segment => (
                  <span
                      key={segment.status}
                      className={
                          `understanding-rounds-segment ` +
                          `understanding-rounds-${segment.status.toLowerCase()}`
                      }
                      style={{
                        flexGrow: segment.count,
                      }}
                      title={
                          `${statusLabels[segment.status]} ` +
                          `${segment.count}명 ` +
                          `(${formatRatio(segment.value)})`
                      }
                  >
                            {segment.width >= 18 && (
                                <span>
                                    {formatRatio(segment.value)}
                                </span>
                            )}
                        </span>
              ))}

            </div>
        ) : (
            <div className="understanding-rounds-empty">
              응답 없음
            </div>
        )}

        <details className="understanding-rounds-details">

          <summary>
            상세 결과 보기
          </summary>

          <div className="understanding-rounds-detail-body">

            <div className="understanding-rounds-values">

              {statusOrder.map(status => (
                  <div
                      key={status}
                      className={
                          `understanding-rounds-value ` +
                          `understanding-rounds-${status.toLowerCase()}`
                      }
                  >

                                <span>
                                    {statusLabels[status]}
                                </span>

                    <strong>
                      {hasResponses
                          ? formatRatio(values[status])
                          : '—'}
                    </strong>

                    <small>
                      {round.counts?.[status] ?? 0}명
                    </small>

                  </div>
              ))}

            </div>

            <dl className="understanding-rounds-totals">

              <div>
                <dt>전체 참여</dt>
                <dd>{total}명</dd>
              </div>

              <div>
                <dt>응답률</dt>
                <dd>
                  {total > 0
                      ? formatRatio(responseRate)
                      : '—'}
                </dd>
              </div>

              <div>
                <dt>미응답</dt>
                <dd>{unanswered}명</dd>
              </div>

              <div>
                <dt>이해 부족 비율</dt>
                <dd>
                  {hasResponses
                      ? formatRatio(round.lackRatio)
                      : '—'}
                </dd>
              </div>

            </dl>

            {round.lowUnderstanding == null && (
                <p className="understanding-rounds-note">
                  {failed
                      ? '이해 부족 판정을 확인하지 못했습니다.'
                      : '이해 부족 판정 확인 중입니다.'}
                </p>
            )}

          </div>

        </details>

      </li>
  )
}