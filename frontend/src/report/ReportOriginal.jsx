import React from 'react'

const koreanClock = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul', hourCycle: 'h23', hour: '2-digit', minute: '2-digit', second: '2-digit',
})

export function ClockTime({ value }) {
  const date = new Date(value)
  if (!value || !Number.isFinite(date.getTime())) return '시각 확인 필요'
  const fraction = date.getMilliseconds() ? `.${String(date.getMilliseconds()).padStart(3, '0')}` : ''
  return <time dateTime={value}>{koreanClock.format(date)}{fraction}</time>
}

export function SpeechOriginal({ segment }) {
  return <>
    <p className="report-muted">발언 시각 <ClockTime value={segment.startAt} /> ~ <ClockTime value={segment.endAt} /> (한국 시간)</p>
    <p className="report-original">{segment.transcript}</p>
  </>
}

export function ChatOriginal({ message, quote }) {
  const start = quote ? message.text.indexOf(quote) : -1
  return <>
    <p className="report-muted">작성 시각 <ClockTime value={message.createdAt} /> (한국 시간)</p>
    <p className="report-original">{start < 0 ? message.text : <>
      {message.text.slice(0, start)}<mark>{quote}</mark>{message.text.slice(start + quote.length)}
    </>}</p>
  </>
}
