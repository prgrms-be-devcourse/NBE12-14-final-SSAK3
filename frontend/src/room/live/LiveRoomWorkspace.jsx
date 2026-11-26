import React, {
    useCallback,
    useEffect,
    useRef,
    useState,
    useSyncExternalStore,
} from 'react'

import { AudienceRoomPanel } from '../../ai/AudienceAnalysisPage.jsx'
import { ChatPanel } from '../../chat/ChatPanel.jsx'
import { SttPanel } from '../../stt/SttPanel.jsx'
import { PollCreatePanel } from '../../poll/PollCreatePanel.jsx'
import { PollResults } from '../../poll/PollResults.jsx'
import { UnderstandingSummary } from '../../poll/UnderstandingSummary.jsx'
import { UnderstandingRounds } from '../../poll/UnderstandingRounds.jsx'
import { watchPollResults } from '../../poll/watchPollResults.js'
import { Icon } from '../../ui/Icon.jsx'

import { LiveAnalysisPanel } from './LiveAnalysisPanel.jsx'
import './live-room.css'

const emptySubscribe = () => () => {}
const emptySnapshot = () => null

const recorderLabels = {
    loading: '녹음 확인 중',
    idle: '녹음 대기',
    starting: '녹음 준비 중',
    listening: '녹음 중',
    paused: '녹음 중단',
    stopping: '기록 저장 중',
    ended: '녹음 종료',
    blocked: '녹음 연결 불가',
}

function elapsed(startedAt, now) {
    const start = Date.parse(startedAt)

    if (!Number.isFinite(start)) return '—'

    const seconds = Math.max(0, Math.floor((now - start) / 1000))

    return [
        Math.floor(seconds / 3600),
        Math.floor(seconds / 60) % 60,
        seconds % 60,
    ].map(value => String(value).padStart(2, '0')).join(':')
}

function LiveStatus({ startedAt, recording }) {
    const [now, setNow] = useState(Date.now)

    useEffect(() => {
        const timer = window.setInterval(() => setNow(Date.now()), 1000)
        return () => window.clearInterval(timer)
    }, [])

    const active = recording?.phase === 'listening'

    return <div className="rl-status">

    <span className="rl-live-label">
      <i aria-hidden="true" />
      강연 진행 중
    </span>

        <span>
      <Icon name="clock" size={18} />
            {elapsed(startedAt, now)}
    </span>

        <span className={active ? 'rl-recording' : ''}>
      <i aria-hidden="true" />
            {recorderLabels[recording?.phase] || '녹음 확인 중'}
    </span>

    </div>
}

function useLivePolls(roomId) {
    const [understanding, setUnderstanding] = useState(null)
    const [general, setGeneral] = useState(null)
    const [connected, setConnected] = useState(false)
    const [denied, setDenied] = useState(false)

    useEffect(() => {
        const common = {
            roomId,
            onConnection: setConnected,
            onDenied: () => setDenied(true),
        }

        const first = watchPollResults({
            ...common,
            type: 'UNDERSTANDING',
            onPolls: setUnderstanding,
        })

        const second = watchPollResults({
            ...common,
            type: 'GENERAL',
            onPolls: setGeneral,
        })

        return () => {
            first.close()
            second.close()
        }
    }, [roomId])

    return { understanding, general, connected, denied }
}

function LatestUnderstanding({ check, loaded, connected, denied }) {
    const tones = {
        UNDERSTOOD: 'good',
        CONFUSED: 'mixed',
        NOT_UNDERSTOOD: 'low',
        '이해됨': 'good',
        '헷갈림': 'mixed',
        '모르겠음': 'low',
    }

    const percent = value =>
        Number.isFinite(value) ? `${value.toFixed(1)}%` : '—'

    const tone = option =>
        tones[option.understandingStatus] ||
        tones[option.content] ||
        'neutral'

    const hasResponses = check?.respondedParticipants > 0

    return <section className="rl-understanding">

        <div className="rl-understanding-head">
            <h3>최근 이해도 체크</h3>

            {!denied && check && <span>
        {check.status === 'OPEN' ? '진행 중' : '마감'}
                {' · '}
                {check.respondedParticipants}/{check.totalParticipants}명 응답
      </span>}
        </div>

        {denied ? <p role="alert">
            이해도 결과를 조회할 수 없습니다.
        </p> : !loaded ? <p role="status">
            이해도 결과 확인 중입니다.
        </p> : !check ? <p>
            아직 표시할 이해도 체크 결과가 없습니다.
        </p> : <>

            <div className="rl-understanding-values">

                {check.options.map(option => <div
                    key={option.optionId}
                    className={`rl-tone-${tone(option)}`}
                >
                    <strong>
                        {hasResponses ? percent(option.percentageOfResponses) : '—'}
                    </strong>

                    <span>{option.content}</span>
                </div>)}

            </div>

            {hasResponses && <div
                className="rl-understanding-bar"
                aria-hidden="true"
            >
                {check.options.filter(option => option.count > 0).map(option =>
                    <span
                        key={option.optionId}
                        className={`rl-tone-${tone(option)}`}
                        style={{ flex: option.count }}
                    />,
                )}
            </div>}

            <p>
                {hasResponses
                    ? `응답자 기준 · 응답률 ${percent(check.responseRate)} · 미응답 ${check.unrespondedParticipants}명`
                    : '아직 응답이 없습니다.'}
            </p>

        </>}

        {!denied && !connected && <p role="status">
            연결 확인 중 · 조회한 결과만 표시합니다.
        </p>}

    </section>
}

export function LiveRoomWorkspace({
                                      room,
                                      userId,
                                      onRecorder,
                                      ending,
                                      onAccessError,
                                  }) {
    const [recorder, setRecorder] = useState(null)
    const [tool, setTool] = useState(null)

    const dialog = useRef(null)

    // 콜백 참조가 바뀌어 녹음 컨트롤러가
    // 재생성되지 않도록 유지한다.
    const registerRecorder = useCallback(value => {
        setRecorder(value)
        onRecorder?.(value)
    }, [onRecorder])

    const recording = useSyncExternalStore(
        recorder?.subscribe || emptySubscribe,
        recorder?.getSnapshot || emptySnapshot,
    )

    const polls = useLivePolls(room.roomId)

    const currentPoll = [
        ...(polls.understanding || []),
        ...(polls.general || []),
    ].find(poll => poll.status === 'OPEN')

    const recordingProblem = recording && (
        recording.retryPaused ||
        recording.archived ||
        recording.stopPending ||
        recording.phase === 'blocked' ||
        recording.authOk === false
    )

    const pollTool = tool === 'poll' || tool === 'understanding'

    useEffect(() => {
        const node = dialog.current

        if (!tool || !node) return

        if (!node.open) node.showModal()

        if (tool === 'understanding') {
            node.querySelector('.room-poll-understanding')
                ?.scrollIntoView({ block: 'nearest' })
        } else {
            node.scrollTop = 0
        }
    }, [tool])

    function openPoll() {
        setTool(currentPoll?.type === 'UNDERSTANDING'
            ? 'understanding'
            : 'poll')
    }

    const titles = {
        poll: '투표 및 결과',
        understanding: '이해도 체크',
        recording: '발언 기록',
    }

    return <div className="react-live-room">

        <div className="rl-main">

            <LiveStatus
                startedAt={room.startedAt}
                recording={recording}
            />

            <AudienceRoomPanel
                roomId={room.roomId}
                onAccessError={onAccessError}
                renderPanel={({ state, actions }) =>
                    <LiveAnalysisPanel
                        roomId={room.roomId}
                        state={state}
                        actions={actions}
                        overview={
                            <LatestUnderstanding
                                check={polls.understanding?.[0]}
                                loaded={polls.understanding !== null}
                                connected={polls.connected}
                                denied={polls.denied}
                            />
                        }
                    />
                }
            />

            {currentPoll && <div className="rl-notice" role="status">

                <div>
                    <strong>
                        {currentPoll.type === 'UNDERSTANDING'
                            ? '이해도 체크'
                            : '투표'}
                        {polls.connected ? ' 진행 중' : ' 상태 확인 필요'}
                    </strong>

                    <p>{currentPoll.question}</p>
                </div>

                <button
                    type="button"
                    className="room-secondary rl-button"
                    onClick={openPoll}
                >
                    확인하기
                </button>

            </div>}

            {recordingProblem && <div className="rl-notice rl-warning" role="status">

                <strong>발언 기록의 저장 상태를 확인해 주세요.</strong>

                <button
                    type="button"
                    className="room-secondary rl-button"
                    onClick={() => setTool('recording')}
                >
                    확인하기
                </button>

            </div>}

            <nav className="rl-toolbar" aria-label="강연 진행 도구">

                <button
                    type="button"
                    className="room-secondary rl-button rl-primary"
                    onClick={() => setTool('poll')}
                >
                    <Icon name="chart" size={21} />
                    투표 만들기
                </button>

                <button
                    type="button"
                    className="room-secondary rl-button"
                    onClick={() => setTool('understanding')}
                >
                    <Icon name="check" size={21} />
                    이해도 체크
                </button>

                <button
                    type="button"
                    className="room-secondary rl-button"
                    onClick={() => setTool('recording')}
                >
                    <Icon name="mic" size={21} />
                    발언 기록

                    {recording?.phase === 'listening' &&
                        <span className="rl-rec-tag">REC</span>}
                </button>

            </nav>

        </div>

        <aside className="rl-chat" aria-label="실시간 채팅">

            <ChatPanel
                roomId={room.roomId}
                live
                showAvatars
                viewer={{ type: 'HOST', id: userId }}
                since={room.startedAt}
                onAccessError={onAccessError}
            />

        </aside>

        {/* 기존 컴포넌트는 닫힌 모달 안에서도 유지한다.
        녹음 중 모달을 닫아도 SttPanel을 제거하지 않는다. */}

        <dialog
            ref={dialog}
            className="rl-dialog"
            aria-labelledby="rl-dialog-title"
            onClose={() => setTool(null)}
        >

            <div className="rl-dialog-head">

                <h2 id="rl-dialog-title">
                    {titles[tool] || '강연 진행 도구'}
                </h2>

                <button
                    type="button"
                    className="room-secondary rl-button"
                    aria-label="닫기"
                    onClick={() => dialog.current?.close()}
                >
                    <Icon name="x" size={22} />
                </button>

            </div>

            <div className="rl-dialog-body">

                <div hidden={!pollTool}>

                    {/* 일반 투표와 이해도 체크는
              기존 컴포넌트 하나를 공유한다. */}
                    <PollCreatePanel
                        roomId={room.roomId}
                        ending={ending}
                    />

                    <PollResults roomId={room.roomId} />

                    <UnderstandingSummary roomId={room.roomId} />

                    <UnderstandingRounds roomId={room.roomId} />

                </div>

                <div
                    className="room-recording"
                    hidden={tool !== 'recording'}
                >

                    <SttPanel
                        roomId={room.roomId}
                        userId={userId}
                        onRecorder={registerRecorder}
                        ending={ending}
                    />

                </div>

            </div>

        </dialog>

    </div>
}
