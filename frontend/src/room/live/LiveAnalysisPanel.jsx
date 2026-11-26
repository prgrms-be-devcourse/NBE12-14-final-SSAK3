import React, { useEffect, useRef, useState } from 'react'
import { Icon } from '../../ui/Icon.jsx'
import { openRoomSocket } from '../../ws/roomSocket.js'

const categories = {
    QUESTION: '질문',
    OPINION: '의견',
    REQUEST: '요청·제안',
    CONFUSION: '이해 부족',
    MIXED: '혼합',
}

const timeFormat = new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
})

function clock(value) {
    if (!value || !Number.isFinite(Date.parse(value))) return '—'

    return timeFormat.format(new Date(value))
}

function completedTime(analysis) {
    return Date.parse(analysis.completedAt || analysis.windowEndAt) || 0
}

function SparkleIcon() {
    return <svg
        width="32"
        height="32"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
    >
        <path d="M11 3c-1 5-3 7-8 8 5 1 7 3 8 8 1-5 3-7 8-8-5-1-7-3-8-8Z" />
        <path d="M19 2v6M16 5h6" />
    </svg>
}

function BellIcon() {
    return <svg
        width="20"
        height="20"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
    >
        <path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9Z" />
        <path d="M10 21h4" />
    </svg>
}

function useAnalysisSocket(roomId, refresh) {
    const refreshRef = useRef(refresh)

    refreshRef.current = refresh

    useEffect(() => {
        const socket = openRoomSocket({
            role: 'HOST',

            subscriptions: [{
                destination: `/topic/rooms/${roomId}/ai`,
                headers: { 'X-Chat-Role': 'HOST' },

                handler(frame) {
                    try {
                        const event = JSON.parse(frame.body)

                        if (
                            event.type === 'AI_ANALYSIS_COMPLETED' &&
                            String(event.data?.roomId) === String(roomId)
                        ) {
                            refreshRef.current?.()
                        }
                    } catch {
                        // 잘못된 이벤트는 무시한다.
                        // 기존 REST 주기 조회는 계속 유지된다.
                    }
                },
            }],

            onConnect: () => refreshRef.current?.(),
        })

        return () => socket.close()
    }, [roomId])
}

function useQuestionNotifications(state) {
    const [unread, setUnread] = useState(() => new Set())
    const [notice, setNotice] = useState(null)

    const initialized = useRef(false)
    const known = useRef(new Map())
    const latestTime = useRef(0)

    useEffect(() => {
        if (!state.loaded || state.error || state.fatal) return

        const analyses = state.analyses

        // 최초 접속 시 기존 질문은 알림으로 표시하지 않는다.
        if (!initialized.current) {
            analyses.forEach(analysis => {
                known.current.set(analysis.analysisId, analysis.status)

                if (analysis.status === 'COMPLETED') {
                    latestTime.current = Math.max(
                        latestTime.current,
                        completedTime(analysis),
                    )
                }
            })

            latestTime.current = Math.max(
                latestTime.current,
                Date.parse(state.lastCompletedAt) || 0,
            )

            initialized.current = true
            return
        }

        const previousTime = latestTime.current
        const newQuestions = []

        for (const analysis of analyses) {
            const previous = known.current.get(analysis.analysisId)

            known.current.set(analysis.analysisId, analysis.status)

            if (
                analysis.status !== 'COMPLETED' ||
                previous === 'COMPLETED'
            ) continue

            const finishedNow =
                previous === 'PROCESSING' ||
                previous === 'PENDING' ||
                previous === 'FAILED' ||
                completedTime(analysis) > previousTime

            latestTime.current = Math.max(
                latestTime.current,
                completedTime(analysis),
            )

            // 과거 분석 범위를 확장하여 가져온 기록은
            // 신규 질문으로 처리하지 않는다.
            if (!finishedNow) continue

            for (const topic of analysis.topicGroups || []) {
                if (topic.category === 'QUESTION') {
                    newQuestions.push(topic)
                }
            }
        }

        latestTime.current = Math.max(
            latestTime.current,
            Date.parse(state.lastCompletedAt) || 0,
        )

        if (!newQuestions.length) return

        const distinct = [
            ...new Map(
                newQuestions.map(topic => [topic.topicGroupId, topic]),
            ).values(),
        ]

        setUnread(previous => {
            const next = new Set(previous)

            distinct.forEach(topic => next.add(topic.topicGroupId))

            return next
        })

        setNotice({
            topic: distinct[0].topic,
            count: distinct.length,
        })

    }, [
        state.loaded,
        state.error,
        state.fatal,
        state.analyses,
        state.lastCompletedAt,
    ])

    useEffect(() => {
        if (!notice) return

        const timer = window.setTimeout(() => setNotice(null), 8000)

        return () => window.clearTimeout(timer)
    }, [notice])

    function markRead(id) {
        setUnread(previous => {
            const next = new Set(previous)
            next.delete(id)
            return next
        })
    }

    function clearUnread() {
        setUnread(new Set())
        setNotice(null)
    }

    return {
        unread,
        notice,
        markRead,
        clearUnread,
        dismiss: () => setNotice(null),
    }
}

function TopicCard({
                       topic,
                       state,
                       actions,
                       done,
                       isNew,
                       onToggle,
                   }) {
    const id = topic.topicGroupId
    const open = state.openIds.includes(id)
    const source = state.sources[id]

    return <article className={`rl-topic${done ? ' rl-topic-done' : ''}`}>

        <div className="rl-topic-row">

            <span className="rl-topic-dot" aria-hidden="true" />

            <div className="rl-topic-content">

                <h3>{topic.topic}</h3>

                <div className="rl-topic-meta">
                    <Icon name="message" size={15} />

                    <span>관련 원문 {topic.messageCount}건</span>

                    <span className="rl-category">
            {categories[topic.category] || '분류 확인 필요'}
          </span>

                    {isNew && <span className="rl-new-tag">NEW</span>}
                </div>

            </div>

            <button
                type="button"
                className="room-secondary rl-button rl-complete-button"
                onClick={() => onToggle(id)}
            >
                <Icon name={done ? 'history' : 'check'} size={17} />

                {done
                    ? '확인 취소'
                    : topic.category === 'QUESTION'
                        ? '답변 완료'
                        : '확인 완료'}
            </button>

        </div>

        <div className="rl-topic-tools">

            <details>
                <summary>AI 요약 보기</summary>
                <p>{topic.summary}</p>
            </details>

            <button
                type="button"
                className="room-secondary rl-source-button"
                aria-expanded={open}
                aria-controls={open ? `rl-source-${id}` : undefined}
                onClick={() => actions.toggleSource(id)}
            >
                {open ? '원문 접기' : '근거 원문 보기'}
            </button>

        </div>

        {open && <div className="rl-sources" id={`rl-source-${id}`}>

            <strong>근거 원문 · 실제 청중 채팅</strong>

            {(!source || source.status === 'loading') &&
                <p role="status">원문을 불러오고 있습니다.</p>}

            {source?.status === 'missing' &&
                <p role="alert">근거 원문을 찾을 수 없습니다.</p>}

            {source?.status === 'error' &&
                <div role="alert">
                    <p>원문을 불러오지 못했습니다.</p>

                    <button
                        type="button"
                        className="room-secondary rl-button"
                        onClick={() => actions.retrySource(id)}
                    >
                        다시 시도
                    </button>
                </div>}

            {source?.status === 'ready' && (
                source.messages.length
                    ? <ul>{source.messages.map(message =>
                        <li key={message.messageId}>

                            <div>
                                <strong>{message.nickname}</strong>
                                <time dateTime={message.receivedAt}>
                                    {clock(message.receivedAt)}
                                </time>
                            </div>

                            <p>{message.content}</p>

                        </li>,
                    )}</ul>
                    : <p>표시할 근거 원문이 없습니다.</p>
            )}

        </div>}

    </article>
}

export function LiveAnalysisPanel({
                                      roomId,
                                      state,
                                      actions,
                                      overview,
                                  }) {
    const [tab, setTab] = useState('waiting')
    const [completed, setCompleted] = useState(() => new Set())

    const notifications = useQuestionNotifications(state)

    useAnalysisSocket(roomId, actions.refresh)

    const latest = state.analyses[0]

    const pending = state.candidate &&
        state.candidate.analysisId !== state.current?.analysisId

    // feed.js가 원문 열람 중 유지하고 있는
    // 분석 결과의 범위를 그대로 사용한다.
    const topics = [
        ...new Map(
            [state.current, ...state.visibleAnalyses]
                .filter(analysis => analysis?.status === 'COMPLETED')
                .flatMap(analysis =>
                    analysis.topicGroups.map(topic => [topic.topicGroupId, topic]),
                ),
        ).values(),
    ]

    const waiting = topics.filter(
        topic => !completed.has(topic.topicGroupId),
    )

    const finished = topics.filter(
        topic => completed.has(topic.topicGroupId),
    )

    const selected = tab === 'waiting' ? waiting : finished

    const keywords = [...new Set(
        (state.current?.topicGroups || []).map(topic => topic.topic),
    )].slice(0, 3)

    function showNewQuestions() {
        actions.showLatest()
        notifications.clearUnread()
        setTab('waiting')
    }

    function changeTab(next) {
        if (tab === next) return

        actions.closeSources(state.openIds)
        setTab(next)
    }

    function toggleCompleted(id) {
        setCompleted(previous => {
            const next = new Set(previous)

            if (next.has(id)) next.delete(id)
            else next.add(id)

            return next
        })

        notifications.markRead(id)

        if (state.openIds.includes(id)) {
            actions.toggleSource(id)
        }
    }

    const analysisStatus = {
        PENDING: '새 청중 의견 분석을 기다리고 있습니다.',
        PROCESSING: 'AI가 청중 의견을 분석하고 있습니다.',
        FAILED: '이번 구간의 AI 분석에 실패했습니다.',
    }[latest?.status]

    return <section className="rl-analysis" aria-label="AI 질문·의견 분석">

        <div className="rl-analysis-head">

            <div>
                <h2>
                    <SparkleIcon />
                    AI 질문·의견 분석
                </h2>

                <p>
                    {state.lastSummaryAt
                        ? `최근 유효 분석 · ${clock(state.lastSummaryAt)} 업데이트`
                        : '청중의 질문과 의견을 실시간으로 정리합니다.'}
                </p>
            </div>

            <button
                type="button"
                className="room-secondary rl-new-button"
                disabled={!state.loaded || state.error}
                onClick={showNewQuestions}
            >
                <BellIcon />

                {notifications.unread.size
                    ? `새 질문 ${notifications.unread.size}개`
                    : '새 질문 없음'}

                <Icon name="chevron-right" size={17} />
            </button>

        </div>

        {notifications.notice && !state.error && <div
            className="rl-toast"
            role="status"
        >

            <Icon name="message" size={26} />

            <div>
                <strong>새로운 질문이 확인되었습니다.</strong>

                <p>
                    {notifications.notice.topic}
                    {notifications.notice.count > 1 &&
                        ` 외 ${notifications.notice.count - 1}개 질문 주제`}
                </p>

                <button
                    type="button"
                    className="room-secondary rl-source-button"
                    onClick={showNewQuestions}
                >
                    확인하기 →
                </button>
            </div>

            <button
                type="button"
                className="room-secondary rl-toast-close"
                aria-label="알림 닫기"
                onClick={notifications.dismiss}
            >
                <Icon name="x" size={18} />
            </button>

        </div>}

        {overview}

        {state.error && <div className="room-warning" role="alert">

            <strong>최신 분석 결과를 확인하지 못했습니다.</strong>

            {state.loaded && <p>
                마지막으로 불러온 내용을 표시하고 있습니다.
            </p>}

            <button
                type="button"
                onClick={actions.reconnect}
                disabled={state.busy}
            >
                {state.busy ? '연결 중…' : '다시 연결'}
            </button>

        </div>}

        {analysisStatus && <div
            className={`rl-notice${latest.status === 'FAILED' ? ' rl-warning' : ''}`}
            role="status"
        >
            <div>
                <strong>{analysisStatus}</strong>

                {state.current && <p>
                    마지막 유효 분석 결과를 표시합니다.
                </p>}
            </div>
        </div>}

        {pending && <div className="rl-notice" role="status">

            <span>새 분석이 있습니다. 열어둔 원문을 유지하고 있습니다.</span>

            <button
                type="button"
                className="room-secondary rl-button"
                onClick={showNewQuestions}
            >
                새 요약 보기
            </button>

        </div>}

        <nav className="rl-tabs" aria-label="질문 확인 상태">

            <button
                type="button"
                className="room-secondary rl-tab"
                aria-pressed={tab === 'waiting'}
                onClick={() => changeTab('waiting')}
            >
                확인 필요
                <span>{waiting.length}</span>
            </button>

            <button
                type="button"
                className="room-secondary rl-tab"
                aria-pressed={tab === 'completed'}
                onClick={() => changeTab('completed')}
            >
                확인 완료
                <span>{finished.length}</span>
            </button>

        </nav>

        {keywords.length > 0 && <div className="rl-summary">

            <Icon name="message" size={29} />

            <div>
                <strong>주요 청중 반응</strong>

                <div className="rl-keywords">
                    {keywords.map(keyword =>
                        <span key={keyword}>{keyword}</span>,
                    )}
                </div>

                <p>최근 유효 분석에서 확인된 핵심 주제입니다.</p>
            </div>

        </div>}

        {!state.loaded && !state.error && <div className="rl-empty">

            <SparkleIcon />
            <strong>AI 분석 결과를 확인하고 있습니다.</strong>

        </div>}

        {state.loaded && selected.length === 0 && <div className="rl-empty">

            <Icon name={tab === 'waiting' ? 'message' : 'check'} size={32} />

            <strong>
                {tab === 'waiting'
                    ? '현재 확인할 질문이나 의견이 없습니다.'
                    : '확인 완료한 항목이 없습니다.'}
            </strong>

            <p>
                {tab === 'waiting'
                    ? '새로운 분석 결과가 도착하면 알려드립니다.'
                    : '확인한 질문과 의견은 이곳에 표시됩니다.'}
            </p>

        </div>}

        <div className="rl-question-list">

            {selected.map(topic => <TopicCard
                key={topic.topicGroupId}
                topic={topic}
                state={state}
                actions={actions}
                done={completed.has(topic.topicGroupId)}
                isNew={notifications.unread.has(topic.topicGroupId)}
                onToggle={toggleCompleted}
            />)}

        </div>

        {state.loaded && <div className="rl-analysis-footer">

      <span>
        최근 {state.limit}개 분석 구간 · 확인 상태는 현재 화면에서만 유지
      </span>

            {state.limit < 50 && <button
                type="button"
                className="room-secondary rl-button"
                onClick={actions.more}
                disabled={state.busy || state.error}
            >
                이전 분석 더 보기
            </button>}

        </div>}

        {state.loaded && <details className="rl-history">

            <summary>분석 상태 및 구간 기록</summary>

            <p>마지막 조회 · {clock(state.checkedAt)} (KST)</p>

            <p>마지막 정상 분석 · {clock(state.lastCompletedAt)} (KST)</p>

            {state.analyses.map(analysis => <div
                className="rl-history-row"
                key={analysis.analysisId}
            >
        <span>
          {clock(analysis.windowStartAt)} ~ {clock(analysis.windowEndAt)}
        </span>

                <span>
          {({
              PENDING: '분석 대기',
              PROCESSING: '분석 중',
              FAILED: '분석 실패',
              COMPLETED: '분석 완료',
          })[analysis.status] || analysis.status}
        </span>
            </div>)}

        </details>}

    </section>
}
