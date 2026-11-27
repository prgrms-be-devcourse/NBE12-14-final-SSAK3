import React, {
  useEffect,
  useId,
  useState,
} from 'react'

import { watchPollResults } from './watchPollResults.js'

import './understandingSummary.css'

const percent = value => `${value.toFixed(1)}%`

const time = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})

export function UnderstandingSummary({ roomId }) {
  const [checks, setChecks] = useState([])
  const [connected, setConnected] = useState(false)
  const [denied, setDenied] = useState(false)

  const headingId = useId()

  useEffect(() => {
    setChecks([])
    setConnected(false)
    setDenied(false)

    const subscription = watchPollResults({
      roomId,
      type: 'UNDERSTANDING',
      onPolls: setChecks,
      onConnection: setConnected,
      onDenied: () => setDenied(true),
    })

    return () => subscription.close()
  }, [roomId])

  const current = checks.find(
      check => check.status === 'OPEN',
  )

  // 마감된 회차는 UnderstandingRounds에서만 표시한다.
  if (denied || !current) {
    return null
  }

  return (
      <section
          className="understanding-summary"
          aria-labelledby={headingId}
      >

        <div className="understanding-summary-heading">

          <h2 id={headingId}>
            진행 중인 이해도 체크
          </h2>

          <span>
                    {checks.length}회차
                </span>

          {!connected && (
              <span>연결 확인 중</span>
          )}

        </div>

        <ul
            className="understanding-summary-list"
            aria-label="진행 중인 이해도 체크 목록"
        >
          <UnderstandingCheckCard
              check={current}
              number={checks.length}
          />
        </ul>

      </section>
  )
}

function UnderstandingCheckCard({ check, number }) {
  const openedAt = Date.parse(check.createdAt)

  return (
      <li
          className="understanding-summary-card"
          aria-label={`이해도 체크 ${number}`}
      >

        <div className="understanding-summary-title">

          <strong>
            {number}회차
          </strong>

          {Number.isFinite(openedAt) && (
              <time dateTime={check.createdAt}>
                {time.format(new Date(openedAt))} 시작
              </time>
          )}

          <span className="understanding-summary-open">
                    진행 중
                </span>

        </div>

        <ul className="understanding-summary-options">

          {check.options.map(option => (
              <li key={option.optionId}>

                <div className="understanding-summary-row">

                  <strong>
                    {option.content}
                  </strong>

                  <span>
                                {option.count}명
                            </span>

                </div>

                <div
                    className="understanding-summary-bar"
                    aria-hidden="true"
                >
                            <span
                                style={{
                                  width:
                                      `${option.percentageOfResponses}%`,
                                }}
                            />
                </div>

                <p>
                  응답자의 {percent(option.percentageOfResponses)}
                  {' · '}
                  전체의 {percent(option.percentageOfAllParticipants)}
                </p>

              </li>
          ))}

        </ul>

        <dl className="understanding-summary-totals">

          <div>
            <dt>전체 참여</dt>
            <dd>{check.totalParticipants}명</dd>
          </div>

          <div>
            <dt>응답</dt>
            <dd>
              {check.respondedParticipants}명
              {' '}
              ({percent(check.responseRate)})
            </dd>
          </div>

          <div>
            <dt>미응답</dt>
            <dd>
              {check.unrespondedParticipants}명
            </dd>
          </div>

        </dl>

      </li>
  )
}