import React, { useEffect, useId, useRef, useState } from 'react'
import { remainingSeconds } from './understanding.js'
import { watchUnderstanding } from './watchUnderstanding.js'
import './understanding.css'

const failureText = '전송하지 못했어요. 다시 눌러 주세요.'

export function UnderstandingCheck({ roomId }) {
  const [state, setState] = useState(null)
  const [ended, setEnded] = useState(false)
  const [busy, setBusy] = useState(null)
  const [error, setError] = useState('')
  const [now, setNow] = useState(() => performance.now())
  const [denied, setDenied] = useState(false)
  const watcher = useRef(null)
  const shown = useRef(false)
  const headingId = useId()

  useEffect(() => {
    const subscription = watchUnderstanding({
      roomId,
      onPoll(next) {
        // 열려 있던 체크가 사라지면 마감 안내를 보여 주고, 새 체크가 열리면 지운다.
        if (next) { shown.current = true; setEnded(false) }
        else if (shown.current) { shown.current = false; setEnded(true) }
        setState(next)
        setError('')
      },
      onDenied: () => setDenied(true),
    })
    watcher.current = subscription
    return () => { watcher.current = null; subscription.close() }
  }, [roomId])

  const poll = state?.poll
  useEffect(() => {
    if (!poll) return undefined
    const timer = window.setInterval(() => setNow(performance.now()), 1000)
    return () => window.clearInterval(timer)
  }, [poll])

  const seconds = poll ? remainingSeconds(poll, now) : 0
  // 시간이 다 됐는데 마감 이벤트가 없으면 한 번 더 서버 상태를 확인한다.
  useEffect(() => {
    if (!poll || seconds > 0) return undefined
    const timer = window.setTimeout(() => void watcher.current?.sync(), 1500)
    return () => window.clearTimeout(timer)
  }, [poll, seconds])

  async function choose(optionId) {
    if (!poll || busy !== null || optionId === state.selected || seconds === 0) return
    const pollId = poll.pollId
    setBusy(optionId)
    setError('')
    try {
      await watcher.current.respond(pollId, optionId)
      setState(current => current?.poll.pollId === pollId ? { ...current, selected: optionId } : current)
    } catch (failure) {
      if (failure.code === 'POLL_CLOSED' || failure.code === 'ROOM_ENDED') void watcher.current?.sync()
      else setError(failureText)
    } finally {
      setBusy(null)
    }
  }

  if (denied) return null
  if (!poll) return ended
    ? <section className="understanding" aria-label="이해도 체크"><p role="status" className="understanding-ended">이해도 체크가 마감되었어요.</p></section>
    : null
  const locked = seconds === 0
  return <section className="understanding" aria-labelledby={headingId}>
    <div className="understanding-heading">
      <h2 id={headingId}>{poll.question}</h2>
      <span className="understanding-time" aria-label={`남은 시간 ${seconds}초`}>{seconds}초</span>
    </div>
    <div className="understanding-options" role="group" aria-labelledby={headingId}>
      {poll.options.map(option => <button key={option.optionId} type="button"
        className={option.optionId === state.selected ? 'understanding-selected' : undefined}
        aria-pressed={option.optionId === state.selected}
        disabled={locked || busy !== null}
        onClick={() => void choose(option.optionId)}>
        {option.content}{busy === option.optionId && <span className="understanding-sending"> 전송 중</span>}
      </button>)}
    </div>
    <p role="status" className="understanding-hint">
      {locked ? '마감 중이에요.' : state.selected === null ? '지금 설명이 어땠는지 눌러 주세요.' : '마감 전에는 선택을 바꿀 수 있어요.'}
    </p>
    {error && <p role="alert" className="understanding-error">{error}</p>}
  </section>
}
