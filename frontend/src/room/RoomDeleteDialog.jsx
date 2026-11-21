import React, { useEffect, useRef, useState } from 'react'
import { request } from '../stt/api.js'

export function RoomDeleteDialog({ room, formattedTime, uncertainRooms, onFinish, onClose, onDenied }) {
  const [phase, setPhase] = useState(() => uncertainRooms.has(room.roomId) ? 'unknown' : 'confirm')
  const dialog = useRef(null)
  const active = useRef(false)
  const busy = useRef(false)
  const path = `/rooms/${encodeURIComponent(room.roomId)}`

  useEffect(() => {
    const element = dialog.current
    active.current = true
    element.showModal()
    return () => { active.current = false; element.close() }
  }, [])

  function handleKnownError(error) {
    if (error.status === 401 || error.status === 403) onDenied(error)
    else if (error.status === 404 && error.code === 'ROOM_NOT_FOUND') onFinish('absent')
    else if (error.status === 409 && error.code === 'INVALID_ROOM_STATUS') onFinish('started')
    else return false
    return true
  }

  function cancel() {
    if (busy.current) return
    dialog.current.close()
    onClose()
    const trigger = document.getElementById(`room-menu-button-${room.roomId}`) || document.getElementById('host-live-rooms')
    trigger?.focus({ preventScroll: true })
  }

  async function checkStatus() {
    setPhase('checking')
    try {
      const detail = await request(path)
      if (!active.current) return
      if (detail?.status === 'READY') {
        uncertainRooms.delete(room.roomId)
        setPhase('retry')
      } else if (detail?.status === 'LIVE' || detail?.status === 'ENDED') {
        onFinish('started')
      } else setPhase('unknown')
    } catch (error) {
      if (active.current && !handleKnownError(error)) setPhase('unknown')
    }
  }

  async function submit() {
    if (busy.current) return
    busy.current = true
    try {
      if (phase === 'unknown') {
        await checkStatus()
        return
      }
      setPhase('deleting')
      uncertainRooms.add(room.roomId)
      try {
        await request(path, { method: 'DELETE' })
        if (active.current) onFinish('deleted')
      } catch (error) {
        if (active.current && !handleKnownError(error)) await checkStatus()
      }
    } finally {
      busy.current = false
    }
  }

  const waiting = phase === 'deleting' || phase === 'checking'
  const feedback = {
    deleting: '강연방을 삭제하고 있습니다.',
    checking: '삭제 결과를 확인하기 위해 강연방 상태를 조회하고 있습니다.',
    unknown: '강연방 상태를 확인하지 못했습니다. 다시 확인해 주세요.',
    retry: '확인 결과 아직 준비 중인 강연방입니다. 삭제하려면 영구 삭제를 다시 눌러 주세요.',
  }
  return <dialog ref={dialog} className="room-dialog host-nav-dialog room-delete-dialog"
    aria-labelledby="room-delete-title" aria-describedby="room-delete-description"
    onCancel={event => { event.preventDefault(); cancel() }}>
    <h2 id="room-delete-title">강연방을 삭제할까요?</h2>
    <p className="room-delete-details"><strong>{room.title}</strong>
      {room.createdAt && <time dateTime={room.createdAt}>{formattedTime} 생성 (한국 시간)</time>}</p>
    <p id="room-delete-description">이 강연방과 청중 입장 기록이 영구 삭제됩니다. 삭제 후에는 복구할 수 없습니다.</p>
    {feedback[phase] && <p role="status">{feedback[phase]}</p>}
    <div>
      <button type="button" className="room-secondary" autoFocus disabled={waiting} onClick={cancel}>취소</button>
      <button type="button" className={phase === 'unknown' ? 'room-secondary' : 'room-delete-confirm'}
        disabled={waiting} onClick={submit}>{phase === 'unknown' || phase === 'checking' ? '상태 다시 확인' : '영구 삭제'}</button>
    </div>
  </dialog>
}
