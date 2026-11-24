import React, { useEffect, useRef, useState } from 'react'
import { request } from '../stt/api.js'
import { openRoomSocket } from '../ws/roomSocket.js'
import './poll.css'

export function PollVotePanel({ roomId, onAccessError }) {
    const [poll, setPoll] = useState(null)
    const [checked, setChecked] = useState(false)
    const [loadError, setLoadError] = useState('')
    const [choice, setChoice] = useState(null)
    const [savedChoice, setSavedChoice] = useState(null)
    const [restoring, setRestoring] = useState(false)
    const [restoreError, setRestoreError] = useState('')
    const [busy, setBusy] = useState(false)
    const [message, setMessage] = useState('')
    const [now, setNow] = useState(Date.now())
    const [revision, setRevision] = useState(0)

    const offset = useRef(0)
    const pending = useRef(false)
    const reload = useRef(null)
    const onAccess = useRef(onAccessError)
    onAccess.current = onAccessError

    const base = `/rooms/${encodeURIComponent(roomId)}/polls`

    useEffect(() => {
        let active = true
        let running = false
        let again = false

        async function refresh() {
            if (!active || pending.current || document.hidden || !navigator.onLine) return

            // 조회 중에 마감·생성 이벤트가 오면 그 조회 결과는 낡았을 수 있으므로 끝난 뒤 한 번 더 조회한다.
            if (running) {
                again = true
                return
            }

            running = true

            try {
                const result = await request(`${base}?status=OPEN`)
                if (!active) return

                if (!Array.isArray(result?.polls)) {
                    throw new Error('INVALID_POLL_LIST')
                }

                setPoll(result.polls[0] ?? null)

                if (result.serverTime) {
                    offset.current = Date.parse(result.serverTime) - Date.now()
                }

                setLoadError('')
            } catch (e) {
                if (!active) return

                setLoadError('투표 목록을 불러오지 못했습니다.')

                if (e.status === 401 || e.status === 403) {
                    onAccess.current?.(e)
                }
            } finally {
                running = false
                if (active) setChecked(true)

                if (again && active) {
                    again = false
                    void refresh()
                }
            }
        }

        reload.current = refresh
        void refresh()

        const timer = window.setInterval(refresh, 5000)
        const visible = () => {
            if (!document.hidden) void refresh()
        }

        document.addEventListener('visibilitychange', visible)
        window.addEventListener('online', visible)

        return () => {
            active = false
            window.clearInterval(timer)
            document.removeEventListener('visibilitychange', visible)
            window.removeEventListener('online', visible)
        }
    }, [base, revision])

    // 투표가 열리거나 마감되면 서버가 알려 주므로, 5초 조회를 기다리지 않고 바로 목록을 다시 받는다.
    // 단순 브로커는 놓친 이벤트를 다시 보내지 않아서 5초 조회와 재연결 때의 조회는 그대로 둔다.
    useEffect(() => {
        const socket = openRoomSocket({
            role: 'PARTICIPANT',
            subscriptions: [{
                destination: `/topic/rooms/${roomId}/polls`,
                handler(frame) {
                    let event
                    try { event = JSON.parse(frame.body) } catch { return }

                    if (event?.data?.roomId !== Number(roomId)) return
                    if (event.type === 'POLL_CREATED' || event.type === 'POLL_CLOSED') void reload.current?.()
                },
            }],
            onConnect: () => void reload.current?.(),
            onAuthError: error => onAccess.current?.(error),
            refresh: () => reload.current?.(),
        })

        return () => socket.close()
    }, [roomId])

    useEffect(() => {
        if (poll?.type !== 'GENERAL') return

        let active = true

        setChoice(null)
        setSavedChoice(null)
        setRestoring(true)
        setRestoreError('')

        request(`${base}/${poll.pollId}/responses/me`)
            .then(result => {
                if (!active) return

                const optionId = result?.optionId ?? null
                setChoice(optionId)
                setSavedChoice(optionId)
            })
            .catch(e => {
                if (!active) return

                setRestoreError('이전 응답을 확인하지 못했습니다.')

                if (e.status === 401 || e.status === 403) {
                    onAccess.current?.(e)
                }
            })
            .finally(() => {
                if (active) setRestoring(false)
            })

        return () => {
            active = false
        }
    }, [base, poll?.pollId, poll?.type, revision])

    useEffect(() => {
        const timer = window.setInterval(() => setNow(Date.now()), 1000)
        return () => window.clearInterval(timer)
    }, [])

    function refresh() {
        setRevision(value => value + 1)
    }

    const remaining = poll
        ? Math.max(0, Math.ceil(
            (Date.parse(poll.closesAt) - now - offset.current) / 1000,
        ))
        : 0

    const disabled = Boolean(loadError) || Boolean(restoreError) ||
        restoring || busy || remaining === 0

    async function vote(event) {
        event.preventDefault()

        if (pending.current || disabled || !poll ||
            poll.type !== 'GENERAL' || choice == null) return

        pending.current = true
        setBusy(true)
        setMessage('')

        try {
            const result = await request(`${base}/${poll.pollId}/responses/me`, {
                method: 'PUT',
                body: { optionId: choice },
            })

            setSavedChoice(result.optionId)
            setChoice(result.optionId)
            setMessage('선택이 저장되었습니다. 마감 전까지 변경할 수 있습니다.')
        } catch (e) {
            if (e.code === 'POLL_CLOSED' || e.code === 'ROOM_ENDED') {
                setMessage('투표가 마감되었습니다.')
                refresh()
            } else if (e.status === 401 || e.status === 403) {
                setMessage('참여 세션을 확인할 수 없습니다.')
                onAccess.current?.(e)
            } else if (e.code === 'INVALID_POLL_OPTION') {
                setMessage('유효하지 않은 선택지입니다.')
            } else {
                setMessage('응답 저장 여부가 불확실합니다. 상태 다시 확인을 눌러 확인해 주세요.')
            }
        } finally {
            pending.current = false
            setBusy(false)
        }
    }

    return <section className="participation-poll" aria-labelledby="audience-poll-title">
        <h2 id="audience-poll-title">실시간 투표</h2>

        {!checked ? <p role="status">투표를 확인하고 있습니다.</p> : <>
            {loadError && <p role="alert">{loadError}</p>}

            {!poll ? <p>현재 진행 중인 투표가 없습니다.</p>
                : poll.type !== 'GENERAL'
                    ? <p>현재 이해도 체크가 진행 중입니다.</p>
                    : <form onSubmit={vote}>
                        <p className="participation-poll-question">{poll.question}</p>

                        <p className="participation-poll-time">
                            남은 시간: {remaining}초
                        </p>

                        <fieldset disabled={disabled}>
                            <legend>답변을 선택하세요</legend>

                            {poll.options?.map(option =>
                                <label className="participation-poll-option"
                                       key={option.optionId}>
                                    <input
                                        type="radio"
                                        name={`poll-${poll.pollId}`}
                                        checked={choice === option.optionId}
                                        onChange={() => {
                                            setChoice(option.optionId)
                                            setMessage('')
                                        }} />
                                    <span>{option.content}</span>
                                </label>
                            )}
                        </fieldset>

                        {savedChoice != null && <p className="participation-poll-saved">
                            저장된 응답이 있습니다. 마감 전까지 변경할 수 있습니다.
                        </p>}

                        {restoring && <p role="status">이전 응답 확인 중…</p>}
                        {restoreError && <p role="alert">{restoreError}</p>}
                        {remaining === 0 && <p role="status">제한 시간이 종료되었습니다.</p>}
                        {message && <p role="status">{message}</p>}

                        <button type="submit"
                                disabled={disabled || choice == null || choice === savedChoice}>
                            {busy ? '저장 중…'
                                : savedChoice == null ? '응답 제출' : '선택 변경 저장'}
                        </button>
                    </form>}

            <button type="button"
                    className="participation-poll-refresh"
                    disabled={busy}
                    onClick={refresh}>
                투표 상태 다시 확인
            </button>
        </>}
    </section>
}