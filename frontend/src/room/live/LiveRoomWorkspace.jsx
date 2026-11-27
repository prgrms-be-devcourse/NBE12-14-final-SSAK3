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
import { LiveRecordingControl } from './LiveRecordingControl.jsx'

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

const understandingTones = {
    UNDERSTOOD: 'good',
    CONFUSED: 'mixed',
    NOT_UNDERSTOOD: 'low',
    '이해됨': 'good',
    '헷갈림': 'mixed',
    '모르겠음': 'low',
}

const toolTitles = {
    poll: '투표 및 결과',
    understanding: '이해도 체크',
    understandingResults: '이해도 체크 결과',
    recording: '발언 기록',
}

function elapsed(startedAt, now) {
    const start = Date.parse(startedAt)

    if (!Number.isFinite(start)) return '—'

    const seconds = Math.max(
        0,
        Math.floor((now - start) / 1000),
    )

    return [
        Math.floor(seconds / 3600),
        Math.floor(seconds / 60) % 60,
        seconds % 60,
    ].map(value =>
        String(value).padStart(2, '0'),
    ).join(':')
}

function percent(value) {
    return Number.isFinite(value)
        ? `${value.toFixed(1)}%`
        : '—'
}

function tone(option) {
    return understandingTones[option.understandingStatus] ||
        understandingTones[option.content] ||
        'neutral'
}

function LiveStatus({
                        startedAt,
                        recorder,
                        recording,
                        ending,
                        offline,
                        chatOpen,
                        onToggleChat,
                    }) {
    const [now, setNow] = useState(Date.now)

    useEffect(() => {
        const timer = window.setInterval(
            () => setNow(Date.now()),
            1000,
        )

        return () => window.clearInterval(timer)
    }, [])

    const active = recording?.phase === 'listening'

    return (
        <div className="rl-status">

            <div className="rl-status-info">

                <span className="rl-live-label">
                    <i aria-hidden="true" />
                    강연 진행 중
                </span>

                <span className="rl-elapsed">
                    <Icon name="clock" size={18} />
                    {elapsed(startedAt, now)}
                </span>

                <span
                    className={`rl-record-status${
                        active ? ' rl-recording' : ''
                    }`}
                >
                    <i aria-hidden="true" />

                    {recorderLabels[recording?.phase] ||
                        '녹음 확인 중'}
                </span>

            </div>

            <div className="rl-status-actions">

                <LiveRecordingControl
                    recorder={recorder}
                    ending={ending}
                    offline={offline}
                />

                <span
                    className="rl-action-divider"
                    aria-hidden="true"
                />

                <button
                    type="button"
                    className="room-secondary rl-button rl-chat-toggle"
                    aria-expanded={chatOpen}
                    aria-controls="rl-chat-area"
                    onClick={onToggleChat}
                >
                    <Icon name="message" size={17} />

                    {chatOpen ? '채팅 접기' : '채팅 열기'}

                    <Icon
                        name="chevron-right"
                        size={15}
                        className={
                            chatOpen
                                ? 'rl-chat-toggle-arrow'
                                : 'rl-chat-toggle-arrow is-closed'
                        }
                    />
                </button>

            </div>

        </div>
    )
}

function useLivePolls(roomId) {
    const [understanding, setUnderstanding] = useState(null)
    const [general, setGeneral] = useState(null)
    const [connected, setConnected] = useState(false)
    const [denied, setDenied] = useState(false)

    useEffect(() => {
        setUnderstanding(null)
        setGeneral(null)
        setConnected(false)
        setDenied(false)

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

    return {
        understanding,
        general,
        connected,
        denied,
    }
}

function LatestUnderstanding({
                                 check,
                                 loaded,
                                 connected,
                                 denied,
                                 onOpenCheck,
                                 onOpenResults,
                                 ending,
                                 offline,
                             }) {
    const hasResponses = check?.respondedParticipants > 0

    return (
        <section className="rl-understanding">

            <div className="rl-understanding-head">

                <h3>최근 이해도 체크</h3>

                <button
                    type="button"
                    className="room-secondary rl-button"
                    disabled={denied}
                    onClick={onOpenResults}
                >
                    <Icon name="chart" size={16} />
                    전체 결과 보기
                    <Icon name="chevron-right" size={15} />
                </button>

            </div>

            {denied ? (
                <p role="alert">
                    이해도 결과를 조회할 수 없습니다.
                </p>
            ) : !loaded ? (
                <p role="status">
                    이해도 결과 확인 중입니다.
                </p>
            ) : !check ? (
                <p>
                    아직 이해도 체크 결과가 없습니다.
                    이해도 체크를 시작해 청중의 반응을 확인하세요.
                </p>
            ) : (
                <>
                    <p>
                        {check.status === 'OPEN' ? '진행 중' : '마감'}
                        {' · '}
                        {check.respondedParticipants}/
                        {check.totalParticipants}명 응답
                    </p>

                    <div className="rl-understanding-values">

                        {check.options.map(option => (
                            <div
                                key={option.optionId}
                                className={`rl-tone-${tone(option)}`}
                            >
                                <strong>
                                    {hasResponses
                                        ? percent(option.percentageOfResponses)
                                        : '—'}
                                </strong>

                                <span>{option.content}</span>
                            </div>
                        ))}

                    </div>

                    {hasResponses && (
                        <div
                            className="rl-understanding-bar"
                            aria-hidden="true"
                        >
                            {check.options
                                .filter(option => option.count > 0)
                                .map(option => (
                                    <span
                                        key={option.optionId}
                                        className={`rl-tone-${tone(option)}`}
                                        style={{ flex: option.count }}
                                    />
                                ))}
                        </div>
                    )}

                    <p>
                        {hasResponses
                            ? `응답자 기준 · 응답률 ${percent(check.responseRate)} · 미응답 ${check.unrespondedParticipants}명`
                            : '아직 응답이 없습니다.'}
                    </p>
                </>
            )}

            {!denied && !connected && (
                <p role="status">
                    연결 확인 중 · 조회한 결과만 표시합니다.
                </p>
            )}

            {!denied && check?.status === 'OPEN' && (
                <div className="room-form-actions">
                    <button
                        type="button"
                        className="room-secondary rl-button"
                        disabled={ending || offline}
                        onClick={onOpenCheck}
                    >
                        진행 중인 체크 관리
                    </button>
                </div>
            )}

        </section>
    )
}

export function LiveRoomWorkspace({
                                      room,
                                      userId,
                                      onRecorder,
                                      ending,
                                      offline = false,
                                      onAccessError,
                                  }) {
    const [recorder, setRecorder] = useState(null)
    const [tool, setTool] = useState(null)
    const [chatOpen, setChatOpen] = useState(true)

    const dialog = useRef(null)
    const rememberChatPosition = useRef(null)

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
        recording.hasUnsecuredSegments ||
        recording.storageOk === false ||
        recording.phase === 'blocked' ||
        recording.phase === 'paused' ||
        recording.authOk === false
    )

    const creationTool =
        tool === 'poll' || tool === 'understanding'

    useEffect(() => {
        const node = dialog.current

        if (!tool || !node) return

        if (!node.open) {
            node.showModal()
        }

        node.scrollTop = 0
    }, [tool])

    function toggleChat() {
        if (chatOpen) {
            rememberChatPosition.current?.()
        }

        setChatOpen(open => !open)
    }

    function openPollType(type) {
        setTool(
            type === 'UNDERSTANDING'
                ? 'understanding'
                : 'poll',
        )
    }

    return (
        <div
            className={`react-live-room${
                chatOpen ? '' : ' rl-chat-collapsed'
            }`}
        >

            <div className="rl-main">

                <LiveStatus
                    startedAt={room.startedAt}
                    recorder={recorder}
                    recording={recording}
                    ending={ending}
                    offline={offline}
                    chatOpen={chatOpen}
                    onToggleChat={toggleChat}
                />

                <AudienceRoomPanel
                    roomId={room.roomId}
                    onAccessError={onAccessError}
                    renderPanel={({ state, actions }) => (
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
                                    ending={ending}
                                    offline={offline}
                                    onOpenCheck={() =>
                                        setTool('understanding')
                                    }
                                    onOpenResults={() =>
                                        setTool('understandingResults')
                                    }
                                />
                            }
                        />
                    )}
                />

                {currentPoll && (
                    <div className="rl-notice" role="status">

                        <div>
                            <strong>
                                {currentPoll.type === 'UNDERSTANDING'
                                    ? '이해도 체크'
                                    : '투표'}

                                {polls.connected
                                    ? ' 진행 중'
                                    : ' 상태 확인 필요'}
                            </strong>

                            <p>{currentPoll.question}</p>
                        </div>

                        <button
                            type="button"
                            className="room-secondary rl-button"
                            onClick={() =>
                                openPollType(currentPoll.type)
                            }
                        >
                            확인하기
                        </button>

                    </div>
                )}

                {recordingProblem && (
                    <div
                        className="rl-notice rl-warning"
                        role="status"
                    >
                        <div>
                            <strong>
                                발언 기록의 상태를 확인해 주세요.
                            </strong>

                            {recording.message && (
                                <p>{recording.message}</p>
                            )}
                        </div>

                        <button
                            type="button"
                            className="room-secondary rl-button"
                            onClick={() => setTool('recording')}
                        >
                            확인하기
                        </button>
                    </div>
                )}

                <nav
                    className="rl-toolbar"
                    aria-label="강연 진행 도구"
                >
                    <button
                        type="button"
                        className="room-secondary rl-button rl-primary"
                        disabled={ending}
                        onClick={() => setTool('poll')}
                    >
                        <Icon name="chart" size={21} />
                        투표 만들기
                    </button>

                    <button
                        type="button"
                        className="room-secondary rl-button"
                        disabled={ending}
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
                        <Icon name="file" size={21} />
                        발언 기록

                        {recording?.phase === 'listening' && (
                            <span className="rl-rec-tag">REC</span>
                        )}
                    </button>
                </nav>

            </div>

            {/* 채팅을 닫아도 기존 연결은 유지한다. */}
            <aside
                id="rl-chat-area"
                className="rl-chat"
                aria-label="실시간 채팅"
                hidden={!chatOpen}
            >
                <ChatPanel
                    roomId={room.roomId}
                    live
                    showAvatars
                    visible={chatOpen}
                    rememberPositionRef={rememberChatPosition}
                    viewer={{
                        type: 'HOST',
                        id: userId,
                    }}
                    since={room.startedAt}
                    onAccessError={onAccessError}
                />
            </aside>

            <dialog
                ref={dialog}
                className="rl-dialog"
                aria-labelledby="rl-dialog-title"
                onClose={() => setTool(null)}
            >

                <div className="rl-dialog-head">

                    <h2 id="rl-dialog-title">
                        {toolTitles[tool] || '강연 진행 도구'}
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

                    {/* 생성 상태와 입력값을 유지하기 위해
                        하나의 생성 컴포넌트를 공유한다. */}
                    <div hidden={!creationTool}>

                        <PollCreatePanel
                            roomId={room.roomId}
                            ending={ending || offline}
                            mode={
                                tool === 'understanding'
                                    ? 'UNDERSTANDING'
                                    : 'GENERAL'
                            }
                            onOpenOther={openPollType}
                        />

                    </div>

                    {/* 일반 투표 전용 결과 */}
                    <div hidden={tool !== 'poll'}>

                        {polls.denied ? (
                            <p role="alert">
                                투표 결과를 조회할 수 없습니다.
                            </p>
                        ) : polls.general === null ? (
                            <p role="status">
                                투표 결과를 확인하고 있습니다.
                            </p>
                        ) : polls.general.length === 0 ? (
                            <p className="room-caption">
                                아직 생성된 투표 결과가 없습니다.
                            </p>
                        ) : null}

                        <PollResults roomId={room.roomId} />

                    </div>

                    {/* 이해도 체크 모달에서 결과로 바로 이동 */}
                    <div hidden={tool !== 'understanding'}>

                        <div className="room-form-actions">
                            <button
                                type="button"
                                className="room-secondary rl-button"
                                onClick={() =>
                                    setTool('understandingResults')
                                }
                            >
                                <Icon name="chart" size={18} />
                                이해도 결과 보기
                            </button>
                        </div>

                    </div>

                    {/* 이해도 전용 결과 화면 */}
                    <div hidden={tool !== 'understandingResults'}>

                        {polls.denied ? (
                            <p role="alert">
                                이해도 결과를 조회할 수 없습니다.
                            </p>
                        ) : polls.understanding === null ? (
                            <p role="status">
                                이해도 결과를 확인하고 있습니다.
                            </p>
                        ) : polls.understanding.length === 0 ? (
                            <p className="room-caption">
                                아직 이해도 체크 결과가 없습니다.
                            </p>
                        ) : (
                            <p className="room-caption">
                                총 {polls.understanding.length}회 진행
                                · 진행 중인 체크와 마감된 체크의
                                결과를 확인할 수 있습니다.
                            </p>
                        )}

                        <UnderstandingRounds
                            roomId={room.roomId}
                        />

                        <UnderstandingSummary
                            roomId={room.roomId}
                        />

                        <div className="room-form-actions">
                            <button
                                type="button"
                                className="room-secondary rl-button"
                                disabled={ending || offline}
                                onClick={() => setTool('understanding')}
                            >
                                <Icon name="check" size={18} />
                                이해도 체크 관리
                            </button>
                        </div>

                    </div>

                    {/* 모달을 닫아도 녹음 상태는 유지한다. */}
                    <div
                        className="room-recording"
                        hidden={tool !== 'recording'}
                    >
                        <SttPanel
                            roomId={room.roomId}
                            userId={userId}
                            onRecorder={registerRecorder}
                            ending={ending}
                            externalControls
                        />
                    </div>

                </div>

            </dialog>

        </div>
    )
}