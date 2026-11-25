import React, { useCallback, useEffect, useRef, useState } from 'react'
import { request } from '../stt/api.js'
import { loginFor } from './state.js'
import { RoomDeleteDialog } from './RoomDeleteDialog.jsx'
import { Icon } from '../ui/Icon.jsx'

const pageSize = 3
const roomTime = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul', year: 'numeric', month: 'long', day: 'numeric',
  hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
})

function checkPage(page) {
  if (!Array.isArray(page?.rooms) || (page.nextCursor !== null && typeof page.nextCursor !== 'string')) {
    throw new Error('INVALID_READY_ROOM_PAGE')
  }
  return page
}

function RoomMenu({ room, onDelete }) {
  const [open, setOpen] = useState(false)
  const trigger = useRef(null)
  const item = useRef(null)
  useEffect(() => { if (open) item.current?.focus() }, [open])
  return <span className="room-row-menu"
    onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false) }}
    onKeyDown={event => {
      if (event.key === 'Escape' && open) {
        event.preventDefault(); setOpen(false); trigger.current.focus()
      }
    }}>
    <button ref={trigger} id={`room-menu-button-${room.roomId}`} type="button" className="room-more-trigger"
      aria-label={`${room.title} 메뉴`} aria-haspopup="menu" aria-expanded={open}
      onKeyDown={event => { if (event.key === 'ArrowDown') { event.preventDefault(); setOpen(true) } }}
      onClick={() => setOpen(value => !value)}>⋯</button>
    {open && <span role="menu" aria-label={`${room.title} 작업`} className="room-row-popup">
      <button ref={item} type="button" role="menuitem" onClick={() => {
        setOpen(false); trigger.current.focus(); onDelete(room)
      }}>강연방 삭제</button>
    </span>}
  </span>
}

function RoomRows({ rooms, onDelete }) {
  const ready = Boolean(onDelete)
  return <ul>{rooms.map(room => {
    const timestamp = ready ? room.createdAt : room.startedAt
    const labelId = `host-room-title-${room.roomId}`
    return <li key={room.roomId}>
      <div><strong id={labelId}>{room.title}</strong>
        {timestamp
          ? <time dateTime={timestamp}>{roomTime.format(new Date(timestamp))} {ready ? '생성' : '시작'}</time>
          : <small>{ready ? '생성 일시 미기록' : '시작 일시 미기록'}</small>}
      </div>
      <span className="room-row-actions">
        <a aria-describedby={labelId} href={`/host/rooms/${room.roomId}`}>{ready ? '강연방 열기' : '강연으로 돌아가기'}<Icon name="arrow-right" size={16} /></a>
        {ready && <RoomMenu room={room} onDelete={onDelete} />}
      </span>
    </li>
  })}</ul>
}

export function HomeRoomLists() {
  const [lists, setLists] = useState(null)
  const [error, setError] = useState(null)
  const [moreFailed, setMoreFailed] = useState(false)
  const [pending, setPending] = useState(null)
  const [selected, setSelected] = useState(null)
  const [notice, setNotice] = useState('')
  const readyLimit = useRef(pageSize)
  const currentRequest = useRef(null)
  const removed = useRef(new Map())
  const uncertainRooms = useRef(new Set())
  const resultNotice = useRef(null)
  useEffect(() => { if (notice) resultNotice.current?.focus({ preventScroll: true }) }, [notice])

  const deny = useCallback((error, action = 'list') => {
    currentRequest.current = null
    readyLimit.current = pageSize
    setSelected(null); setLists(null); setNotice(''); setPending(null); setError({ status: error.status, action })
    if (error.status === 401) loginFor('/host/rooms/new')
  }, [])

  const load = useCallback(async (cursor = null) => {
    const more = cursor !== null
    if (document.hidden || (more && currentRequest.current)) return
    // Refreshing the home replaces both lists and supersedes any older cursor response.
    const token = Symbol()
    currentRequest.current = token
    setPending(more ? 'more' : 'home')
    setMoreFailed(false)
    try {
      const path = more ? `/rooms/ready?cursor=${encodeURIComponent(cursor)}` : `/rooms/home?readyLimit=${readyLimit.current}`
      const data = await request(path)
      if (currentRequest.current !== token) return
      if (more) {
        const page = checkPage(data)
        setLists(previous => ({
          ...previous,
          readyRooms: { rooms: [...previous.readyRooms.rooms, ...page.rooms.filter(room => !removed.current.has(room.roomId))], nextCursor: page.nextCursor },
        }))
        readyLimit.current += pageSize
      } else {
        if (!Array.isArray(data?.liveRooms)) throw new Error('INVALID_HOME_ROOM_LIST')
        const ready = checkPage(data.readyRooms)
        setLists({
          liveRooms: data.liveRooms.filter(room => !removed.current.has(room.roomId) || removed.current.get(room.roomId) === 'started'),
          readyRooms: { ...ready, rooms: ready.rooms.filter(room => !removed.current.has(room.roomId)) },
        })
        setError(null)
      }
    } catch (e) {
      if (currentRequest.current !== token) return
      if (e.status === 401 || e.status === 403) {
        deny(e)
      } else if (more) setMoreFailed(true)
      else setError(e)
    } finally {
      if (currentRequest.current === token) {
        currentRequest.current = null
        setPending(null)
      }
    }
  }, [deny])

  function finish(outcome) {
    const { roomId, title } = selected
    uncertainRooms.current.delete(roomId)
    removed.current.set(roomId, outcome)
    currentRequest.current = null
    setPending(null)
    setSelected(null)
    setNotice(outcome === 'deleted' ? `‘${title}’ 강연방을 삭제했습니다.`
      : outcome === 'absent' ? `‘${title}’ 강연방이 더 이상 존재하지 않습니다.`
      : `‘${title}’ 강연이 이미 시작되어 삭제할 수 없습니다.`)
    setLists(previous => previous && ({
      ...previous,
      readyRooms: { ...previous.readyRooms, rooms: previous.readyRooms.rooms.filter(room => room.roomId !== roomId) },
    }))
    load()
  }

  useEffect(() => {
    let active = true
    function refresh() {
      if (active && !document.hidden) load()
    }
    function restorePage(event) {
      if (!event.persisted) return
      readyLimit.current = pageSize
      setLists(null)
      setError(null)
      refresh()
    }
    queueMicrotask(refresh)
    document.addEventListener('visibilitychange', refresh)
    window.addEventListener('pageshow', restorePage)
    return () => {
      active = false
      currentRequest.current = null
      document.removeEventListener('visibilitychange', refresh)
      window.removeEventListener('pageshow', restorePage)
    }
  }, [load])

  return <>
    {notice && <p role="status" className="room-delete-notice" tabIndex={-1} ref={resultNotice}>{notice}</p>}
    <section id="host-live-rooms" tabIndex={-1} className="room-live-list" aria-label="진행 중인 내 강연">
      {error && <div className="room-warning" role="alert">
        <p>{error.status === 403 && error.action === 'delete' ? '이 강연방을 삭제할 권한이 없습니다.'
          : error.status === 403 ? '강연방 목록을 조회할 권한이 없습니다.'
          : lists === null ? '강연방 목록을 불러오지 못했습니다.'
          : lists.liveRooms.length === 0 ? '목록을 갱신하지 못했어요. 마지막 조회 때는 진행 중인 강연이 없었습니다.'
          : '목록을 갱신하지 못했어요. 마지막으로 확인한 목록이에요.'}</p>
        <button type="button" className="room-secondary" disabled={pending === 'home'} onClick={() => load()}>다시 불러오기</button>
      </div>}
      {lists === null && !error && <p role="status">강연방 목록을 불러오고 있습니다.</p>}
      {lists?.liveRooms.length === 0 && !error && <p className="room-empty-live" role="status"><Icon name="radio" size={20} />현재 진행 중인 강연이 없습니다</p>}
      {lists?.liveRooms.length > 0 && <div className="room-card">
        <h2><span className="room-heading-icon"><Icon name="radio" /></span>진행 중인 내 강연</h2>
        <RoomRows rooms={lists.liveRooms} />
      </div>}
    </section>
    {lists?.readyRooms.rooms.length > 0 && <section className="room-live-list room-ready-list" aria-labelledby="host-ready-heading">
      <div className="room-card">
        <h2 id="host-ready-heading"><span className="room-heading-icon"><Icon name="calendar" /></span>준비 중인 강연</h2>
        <RoomRows rooms={lists.readyRooms.rooms} onDelete={room => { setNotice(''); setSelected(room) }} />
        {lists.readyRooms.nextCursor && <div className="room-ready-more">
          {moreFailed && <p role="alert">다음 강연방을 불러오지 못했어요.</p>}
          <button type="button" className="room-secondary" disabled={pending !== null}
            onClick={() => load(lists.readyRooms.nextCursor)}>
            {pending === 'more' ? '불러오는 중…' : moreFailed ? '다시 시도' : '더 보기'}
          </button>
        </div>}
      </div>
    </section>}
    {selected && <RoomDeleteDialog key={selected.roomId} room={selected}
      formattedTime={selected.createdAt && roomTime.format(new Date(selected.createdAt))}
      uncertainRooms={uncertainRooms.current} onFinish={finish} onClose={() => setSelected(null)} onDenied={error => deny(error, 'delete')} />}
  </>
}
