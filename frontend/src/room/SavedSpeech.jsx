import React, { useEffect, useRef, useState } from 'react'
import { sttApi } from '../stt/api.js'
import { terminal } from './state.js'

const reasons = { ERROR: '음성 인식 중단', PERMISSION_DENIED: '마이크 권한 문제', MANUAL_STOP: '수동 중지' }
const clock = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', hourCycle: 'h23',
  hour: '2-digit', minute: '2-digit', second: '2-digit' })

export function SavedSpeech({ roomId, onAccessError }) {
  const [open, setOpen] = useState(false)
  const [rows, setRows] = useState(null)
  const [error, setError] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const access = useRef(onAccessError)
  access.current = onAccessError
  useEffect(() => {
    if (!open || rows) return
    let active = true
    setError(false)
    sttApi(roomId).records().then(({ segments, gaps }) => {
      if (!active) return
      const entries = [
        ...segments.map(s => ({ key: 's' + s.segmentId, time: s.segmentStartAt, text: s.transcript })),
        ...gaps.map(g => ({ key: 'g' + g.gapId, time: g.startedAt, gap: true, end: g.endedAt,
          text: reasons[g.reason] || '전사 누락' })),
      ]
      entries.sort((a, b) => Date.parse(a.time) - Date.parse(b.time))
      setRows(entries)
    }).catch(e => {
      if (!active) return
      if (terminal(e)) access.current(e)
      else setError(true)
    })
    return () => { active = false }
  }, [roomId, open, rows, attempt])
  return <section className="room-card room-records">
    <button className="room-secondary" aria-expanded={open} onClick={() => setOpen(!open)}>
      {open ? '저장된 발언 접기' : '저장된 발언 보기'}</button>
    {open && (error ? <p role="alert">발언 기록을 불러오지 못했습니다.{' '}
      <button onClick={() => setAttempt(n => n + 1)}>다시 불러오기</button></p>
      : !rows ? <p role="status">발언 기록을 불러오고 있습니다.</p>
      : !rows.length ? <p>저장된 발언이나 누락 구간이 없습니다.</p>
      : <ol aria-label="저장된 발언과 누락 구간">{rows.map(row => <li key={row.key} className={row.gap ? 'room-gap' : ''}>
        <time dateTime={row.time}>{clock.format(new Date(row.time))}</time>
        {row.gap && <> ~ {row.end ? clock.format(new Date(row.end)) : '종료 시각 미확인'} · 전사 누락</>}
        <p>{row.text}</p>
      </li>)}</ol>)}
  </section>
}
