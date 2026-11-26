import React, { useLayoutEffect, useRef, useState } from 'react'
import { Link, useLocation, useNavigationType } from 'react-router-dom'
import { useRoomHistory } from './useRoomHistory.js'
import { Icon } from '../ui/Icon.jsx'
import './history.css'

// Entries live only in this document. Reloads and fresh menu navigations always
// start with the first page, even when the URL and signed-in host are identical.
const entries = new Map()
const endedTime = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul', year: 'numeric', month: 'long', day: 'numeric',
  hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
})
const statusText = {
  PENDING: '보고서 생성 대기 중', PROCESSING: '보고서 생성 중', FAILED: '보고서 생성 실패',
}

function HistoryRow({ row, remember }) {
  const roomPath = `/host/rooms/${row.roomId}`
  const titleId = `history-room-title-${row.roomId}`
  return <li>
    <div className="history-room-info">
      <h2 id={titleId}>{row.title}</h2>
      {row.endedAt ? <time dateTime={row.endedAt}><Icon name="clock" size={14} />{endedTime.format(new Date(row.endedAt))} 종료</time>
        : <small>종료 시각 미기록</small>}
    </div>
    <div className="history-row-actions">
      <Link to={roomPath} aria-describedby={titleId}
        onClick={event => remember(event, row.roomId)}>강연 기록 열기<Icon name="arrow-right" size={15} /></Link>
      {row.reportStatus === 'COMPLETED'
        ? <Link className="history-report-link" to={roomPath + '/report'} aria-describedby={titleId}
            onClick={event => remember(event, row.roomId)}>회고 보고서 보기</Link>
        : <span className="history-report-status">{row.reportUnconfirmed ? '보고서 결과 확인 중' : statusText[row.reportStatus] ??
            (row.awaiting ? '보고서 접수 확인 중' : '보고서 접수 확인 필요')}</span>}
    </div>
  </li>
}

export function RoomHistory({ userId }) {
  const location = useLocation()
  const navigation = useNavigationType()
  const key = `${userId}:${location.key}`
  const [restored] = useState(() => navigation === 'POP' ? entries.get(key) : undefined)
  const { view, loadPage } = useRoomHistory(restored)
  const current = useRef(view)
  current.current = view
  const openedId = useRef(null)
  const scrollTop = useRef(restored?.scrollTop ?? 0)

  useLayoutEffect(() => {
    const previousRestoration = window.history.scrollRestoration
    window.history.scrollRestoration = 'manual'
    window.scrollTo(0, restored?.scrollTop ?? 0)
    const onScroll = () => { scrollTop.current = window.scrollY }
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => {
      window.removeEventListener('scroll', onScroll)
      window.history.scrollRestoration = previousRestoration
      if (current.current.rows) entries.set(key, {
        view: { ...current.current, loading: null }, openedId: openedId.current, scrollTop: scrollTop.current,
      })
      else entries.delete(key)
    }
  }, [key, restored])
  function remember(event, id) {
    if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return
    openedId.current = id
    scrollTop.current = window.scrollY
  }

  const notice = view.statusError ? '보고서 상태를 갱신하지 못했어요.'
    : view.pageError === 'latest' ? '최신 강연 기록을 불러오지 못했어요.'
      : view.headError ? '새 강연 기록을 확인하지 못했어요.' : ''
  return <section className="host-history" aria-labelledby="history-title">
    <div className="history-heading">
      <div><h1 id="history-title">내 강연 기록</h1>
        <p>종료된 강연의 기록과 회고 보고서를 확인하세요.</p></div>
      {view.hasNew && <button type="button" className="room-secondary"
        disabled={!!view.loading} onClick={() => loadPage('latest')}>새 강연 기록 보기</button>}
    </div>
    <p className="history-notice" role="status">{notice}</p>
    {view.forbidden ? <p role="alert">강연 기록을 조회할 권한이 없습니다.</p>
      : view.rows === null ? view.pageError === 'first'
        ? <div className="history-empty"><p role="alert">강연 기록을 불러오지 못했어요.</p>
            <button className="room-secondary" onClick={() => loadPage('first')}>다시 불러오기</button></div>
        : <p role="status">강연 기록을 불러오고 있어요.</p>
      : view.rows.length === 0 ? <div className="history-empty">
          <span className="room-heading-icon"><Icon name="history" size={23} /></span>
          <h2>아직 종료한 강연이 없어요.</h2><p>강연을 종료하면 이곳에서 다시 볼 수 있어요.</p></div>
      : <>
        <ul className="history-rows">{view.rows.map(row =>
          <HistoryRow key={row.roomId} row={row} remember={remember} />)}</ul>
        {view.nextCursor && <div className="history-more">
          {view.pageError === 'more' && <p role="alert">다음 강연 기록을 불러오지 못했어요.</p>}
          <button type="button" className="room-secondary" onClick={() => loadPage('more')} disabled={!!view.loading}>
            {view.loading === 'more' ? '불러오는 중…' : view.pageError === 'more' ? '다시 시도' : '더 보기'}
          </button>
        </div>}
      </>}
  </section>
}
