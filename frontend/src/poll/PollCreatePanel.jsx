import React, { useEffect, useRef, useState } from 'react'
import { request } from '../stt/api.js'
import { loginFor } from '../room/state.js'

const emptyDraft = () => ({
    question: '',
    options: ['', ''],
    durationSeconds: '60',
})

export function PollCreatePanel({ roomId, ending = false }) {
    const [draft, setDraft] = useState(emptyDraft)
    const [activePoll, setActivePoll] = useState(null)
    const [checking, setChecking] = useState(true)
    const [loadError, setLoadError] = useState('')
    const [error, setError] = useState('')
    const [busy, setBusy] = useState(false)
    const [unknown, setUnknown] = useState(false)
    const [revision, setRevision] = useState(0)
    const [now, setNow] = useState(Date.now())
    const [understandingSeconds, setUnderstandingSeconds] = useState('60')

    const offset = useRef(0)
    const operation = useRef(0)
    const pending = useRef(false)
    const base = `/rooms/${encodeURIComponent(roomId)}/polls`

    useEffect(() => {
        let active = true
        let running = false

        async function refresh() {
            if (!active || running || pending.current || document.hidden || !navigator.onLine) return

            running = true
            const version = operation.current

            try {
                const result = await request(`${base}?status=OPEN`)
                if (!active || version !== operation.current) return

                if (!Array.isArray(result?.polls)) {
                    throw new Error('INVALID_POLL_LIST')
                }

                setActivePoll(result.polls[0] ?? null)

                if (result.serverTime) {
                    offset.current = Date.parse(result.serverTime) - Date.now()
                }

                setLoadError('')
                setUnknown(false)
            } catch (e) {
                if (!active || version !== operation.current) return

                setLoadError('진행 중인 투표를 확인하지 못했습니다.')

                if (e.status === 401) {
                    loginFor(location.pathname)
                }
            } finally {
                running = false
                if (active && version === operation.current) {
                    setChecking(false)
                }
            }
        }

        void refresh()

        const timer = window.setInterval(refresh, 5000)
        const visible = () => {
            if (!document.hidden) void refresh()
        }

        window.addEventListener('online', visible)
        document.addEventListener('visibilitychange', visible)

        return () => {
            active = false
            window.clearInterval(timer)
            window.removeEventListener('online', visible)
            document.removeEventListener('visibilitychange', visible)
        }
    }, [base, revision])

    useEffect(() => {
        const timer = window.setInterval(() => setNow(Date.now()), 1000)
        return () => window.clearInterval(timer)
    }, [])

    function reload() {
        setRevision(value => value + 1)
    }

    function changeOption(index, value) {
        setDraft(current => ({
            ...current,
            options: current.options.map((option, i) =>
                i === index ? value : option),
        }))
        setError('')
    }

    function addOption() {
        setDraft(current => ({
            ...current,
            options: [...current.options, ''],
        }))
    }

    function removeOption(index) {
        setDraft(current => ({
            ...current,
            options: current.options.filter((_, i) => i !== index),
        }))
        setError('')
    }

    async function submit(event) {
        event.preventDefault()

        if (pending.current || checking || loadError || activePoll || unknown || ending) return

        const question = draft.question.trim()
        const options = draft.options.map(option => option.trim())
        const durationSeconds = Number(draft.durationSeconds)

        if (!question || question.length > 500) {
            setError('투표 질문을 확인해 주세요.')
            return
        }

        if (options.length < 2 || options.length > 5 ||
            options.some(option => !option || option.length > 255)) {
            setError('선택지를 2~5개 입력해 주세요.')
            return
        }

        if (new Set(options).size !== options.length) {
            setError('중복된 선택지가 있습니다.')
            return
        }

        if (!Number.isInteger(durationSeconds) ||
            durationSeconds < 10 || durationSeconds > 300) {
            setError('제한 시간은 10~300초로 설정해 주세요.')
            return
        }

        await createPoll({ type: 'GENERAL', question, options, durationSeconds })
    }

    async function startUnderstanding() {
        if (pending.current || checking || loadError || activePoll || unknown || ending) return

        const durationSeconds = Number(understandingSeconds)

        if (!Number.isInteger(durationSeconds) ||
            durationSeconds < 10 || durationSeconds > 300) {
            setError('제한 시간은 10~300초로 설정해 주세요.')
            return
        }

        await createPoll({ type: 'UNDERSTANDING', durationSeconds })
    }

    async function createPoll(body) {
        pending.current = true
        operation.current++
        setBusy(true)
        setError('')

        try {
            const poll = await request(base, { method: 'POST', body })

            if (!poll?.pollId) throw new Error('POLL_RESPONSE_MISSING')

            setActivePoll(poll)
            setDraft(emptyDraft())

            if (poll.serverTime) {
                offset.current = Date.parse(poll.serverTime) - Date.now()
            }
        } catch (e) {
            if (e.status === 401) {
                loginFor(location.pathname)
            } else if (e.code === 'POLL_ALREADY_OPEN') {
                setError('이미 진행 중인 투표가 있습니다. 상태를 다시 확인합니다.')
                reload()
            } else if (e.code === 'INVALID_INPUT') {
                setError('질문과 선택지, 제한 시간을 확인해 주세요.')
            } else if (e.status === 403) {
                setError('투표를 생성할 권한이 없습니다.')
            } else if (e.code === 'ROOM_NOT_LIVE' || e.code === 'ROOM_ENDED') {
                setError('진행 중인 강연에서만 투표를 생성할 수 있습니다.')
            } else {
                setUnknown(true)
                setError('생성 결과를 확인하지 못했습니다. 중복 생성을 피하려면 상태를 다시 확인해 주세요.')
            }
        } finally {
            pending.current = false
            setBusy(false)
        }
    }

    async function closePoll() {
        if (pending.current || checking || loadError || unknown || ending ||
            !activePoll) return

        pending.current = true
        operation.current++
        setBusy(true)
        setError('')

        try {
            await request(`${base}/${activePoll.pollId}/close`, {
                method: 'POST',
            })

            setActivePoll(null)
            reload()
        } catch (e) {
            if (e.status === 401) {
                loginFor(location.pathname)
            } else if (e.code === 'POLL_ALREADY_CLOSED') {
                reload()
            } else if (e.status === 403) {
                setError('투표를 마감할 권한이 없습니다.')
            } else {
                setUnknown(true)
                setError('마감 결과를 확인하지 못했습니다. 상태를 다시 확인해 주세요.')
            }
        } finally {
            pending.current = false
            setBusy(false)
        }
    }

    const remaining = activePoll
        ? Math.max(0, Math.ceil(
            (Date.parse(activePoll.closesAt) - now - offset.current) / 1000,
        ))
        : 0

    const blocked = busy || ending || checking || Boolean(loadError) || unknown

    return <section className="room-card room-poll" aria-labelledby="poll-title">
        <h2 id="poll-title">일반 투표</h2>

        {checking ? <p role="status">진행 중인 투표를 확인하고 있습니다.</p> : <>
            {loadError && <div className="room-warning" role="alert">
                {loadError}
            </div>}

            {activePoll ? <>
                <p className="room-note" role="status">
                    {activePoll.type === 'GENERAL'
                        ? '진행 중인 투표가 있습니다.'
                        : '이해도 체크가 진행 중입니다.'}
                </p>

                <strong>{activePoll.question}</strong>

                <ol>
                    {activePoll.options?.map(option =>
                        <li key={option.optionId}>{option.content}</li>
                    )}
                </ol>

                <p className="room-caption">
                    남은 시간: {remaining}초 · 투표 번호: {activePoll.pollId}
                </p>

                {error && <p className="room-poll-error" role="alert">{error}</p>}

                <div className="room-form-actions">
                    <button type="button" className="room-secondary"
                            disabled={busy} onClick={reload}>
                        상태 다시 확인
                    </button>

                    <button
                        type="button"
                        disabled={blocked || remaining === 0}
                        onClick={closePoll}>
                        {busy ? '마감 중…' : activePoll.type === 'GENERAL' ? '투표 마감' : '이해도 체크 마감'}
                    </button>
                </div>
            </> : <>
            <form onSubmit={submit}>
                <label htmlFor="poll-question">
                    투표 질문 <span className="room-required">필수</span>
                </label>

                <input id="poll-question"
                       value={draft.question}
                       maxLength={500}
                       required
                       disabled={blocked}
                       placeholder="예: Redis를 사용하는 이유는 무엇일까요?"
                       onChange={e => {
                           setDraft(current => ({ ...current, question: e.target.value }))
                           setError('')
                       }} />

                <small className="room-count">{draft.question.length} / 500</small>

                <fieldset className="room-poll-options" disabled={blocked}>
                    <legend>선택지 (2~5개)</legend>

                    {draft.options.map((option, index) =>
                        <div className="room-poll-option" key={index}>
                            <label htmlFor={`poll-option-${index}`}>
                                선택지 {index + 1}
                            </label>

                            <input id={`poll-option-${index}`}
                                   value={option}
                                   maxLength={255}
                                   required
                                   placeholder={`선택지 ${index + 1} 입력`}
                                   onChange={e => changeOption(index, e.target.value)} />

                            <button type="button" className="room-secondary"
                                    disabled={blocked || draft.options.length <= 2}
                                    onClick={() => removeOption(index)}>
                                삭제
                            </button>
                        </div>
                    )}
                </fieldset>

                <button type="button" className="room-secondary room-poll-add"
                        disabled={blocked || draft.options.length >= 5}
                        onClick={addOption}>
                    + 선택지 추가
                </button>

                <label htmlFor="poll-duration">
                    제한 시간 (초) <span className="room-required">필수</span>
                </label>

                <input id="poll-duration"
                       className="room-poll-duration"
                       type="number"
                       min="10"
                       max="300"
                       step="1"
                       required
                       disabled={blocked}
                       value={draft.durationSeconds}
                       onChange={e => {
                           setDraft(current => ({
                               ...current,
                               durationSeconds: e.target.value,
                           }))
                           setError('')
                       }} />

                <p className="room-caption">10~300초, 기본 60초</p>

                {error && <p className="room-poll-error" role="alert">{error}</p>}

                {(unknown || loadError) && <button type="button"
                                                   className="room-secondary"
                                                   onClick={reload}>
                    투표 상태 다시 확인
                </button>}

                <div className="room-form-actions">
                    <button type="button" className="room-secondary"
                            disabled={blocked}
                            onClick={() => {
                                setDraft(emptyDraft())
                                setError('')
                            }}>
                        입력 초기화
                    </button>

                    <button type="submit" disabled={blocked}>
                        {busy ? '투표 생성 중…' : '투표 생성'}
                    </button>
                </div>
            </form>

            <div className="room-poll-understanding">
                <strong>이해도 체크</strong>

                <p className="room-caption">
                    이해됨·헷갈림·모르겠음 3개 선택지로 청중의 이해도를 확인합니다.
                </p>

                <label htmlFor="understanding-duration">체크 시간 (초)</label>

                <input id="understanding-duration"
                       className="room-poll-duration"
                       type="number"
                       min="10"
                       max="300"
                       step="1"
                       disabled={blocked}
                       value={understandingSeconds}
                       onChange={e => {
                           setUnderstandingSeconds(e.target.value)
                           setError('')
                       }} />

                <button type="button" disabled={blocked} onClick={startUnderstanding}>
                    {busy ? '시작 중…' : '이해도 체크 시작'}
                </button>
            </div>
            </>}
        </>}
    </section>
}