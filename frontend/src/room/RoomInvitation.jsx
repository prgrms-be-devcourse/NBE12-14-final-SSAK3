import React, { useEffect, useLayoutEffect, useRef, useState } from 'react'
import QRCode from 'qrcode'
import { request } from '../stt/api.js'
import { terminal } from './state.js'
import './invitation.css'

const mobileSheet = '(max-width: 760px)'

export function RoomInvitation({ id, open, onClose, onAccessError }) {
  const [info, setInfo] = useState(null)
  const [failed, setFailed] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const access = useRef(onAccessError)
  access.current = onAccessError

  useEffect(() => {
    let active = true
    setInfo(null)
    setFailed(false)
    async function load() {
      if (!active) return
      try {
        const value = await request(`/rooms/${id}/invitation`)
        if (!active) return
        const image = await QRCode.toDataURL(value.qrText, { width: 640, margin: 4, errorCorrectionLevel: 'M' })
        if (active) setInfo({ ...value, image })
      } catch (error) {
        if (!active) return
        if (terminal(error)) access.current(error)
        else setFailed(true)
      }
    }
    queueMicrotask(load)
    return () => { active = false }
  }, [id, attempt])

  return open && <InvitePanel info={info} failed={failed} retry={() => setAttempt(n => n + 1)} onClose={onClose} />
}

function InvitePanel({ info, failed, retry, onClose }) {
  const [large, setLarge] = useState(false)
  const [notice, setNotice] = useState('')
  const dialog = useRef(null)
  const qrButton = useRef(null)
  const previousLarge = useRef(false)
  const close = useRef(onClose)
  close.current = onClose

  useLayoutEffect(() => {
    const panel = dialog.current
    return () => {
      const hadFocus = panel.contains(document.activeElement)
      panel.close()
      if (hadFocus) {
        const triggerId = window.matchMedia(mobileSheet).matches ? 'host-menu-trigger' : 'host-invite-trigger'
        document.getElementById(triggerId)?.focus({ preventScroll: true })
      }
    }
  }, [])

  useLayoutEffect(() => {
    const panel = dialog.current
    function position() {
      const modal = large || window.matchMedia(mobileSheet).matches
      if (panel.open && panel.matches(':modal') !== modal) panel.close()
      if (!modal) {
        const trigger = document.getElementById('host-invite-trigger')
        panel.style.setProperty('--invite-anchor-y', (trigger?.getBoundingClientRect().top ?? 76) + 'px')
      }
      if (!panel.open) {
        if (modal) panel.showModal()
        else panel.show()
      }
    }
    position()
    if (!large && previousLarge.current) qrButton.current?.focus({ preventScroll: true })
    else panel.querySelector('button')?.focus({ preventScroll: true })
    previousLarge.current = large
    window.addEventListener('resize', position)
    return () => window.removeEventListener('resize', position)
  }, [large])

  useEffect(() => {
    const panel = dialog.current
    function outside(event) {
      if (panel.matches(':modal') || panel.contains(event.target) || event.target.closest('#host-invite-trigger')) return
      close.current()
    }
    function escape(event) {
      // Modal sheets and the large QR view use the native dialog cancel event.
      if (event.key === 'Escape' && !event.defaultPrevented && !panel.matches(':modal')) {
        event.preventDefault()
        close.current()
      }
    }
    document.addEventListener('pointerdown', outside)
    document.addEventListener('keydown', escape)
    return () => {
      document.removeEventListener('pointerdown', outside)
      document.removeEventListener('keydown', escape)
    }
  }, [])

  function dismiss() {
    if (large) setLarge(false)
    else close.current()
  }
  async function copyLink() {
    try {
      await navigator.clipboard.writeText(info.joinUrl)
      setNotice('참여 링크를 복사했습니다.')
    } catch { setNotice('복사하지 못했습니다. 링크를 선택해 직접 복사해 주세요.') }
  }

  return <dialog ref={dialog} id="host-invite-panel" className={large ? 'invite-panel invite-qr-view' : 'invite-panel'}
    aria-labelledby="invite-heading" onCancel={event => { event.preventDefault(); dismiss() }}
    onClick={event => {
      if (event.target !== dialog.current || !dialog.current.matches(':modal')) return
      const box = dialog.current.getBoundingClientRect()
      if (event.clientX < box.left || event.clientX > box.right || event.clientY < box.top || event.clientY > box.bottom) dismiss()
    }}>
    <div className="invite-head">
      <h2 id="invite-heading">{large ? 'QR코드로 강연에 참여하세요' : '청중 초대'}</h2>
      <button type="button" className="invite-close" onClick={dismiss} aria-label={large ? '확대된 QR 닫기' : '청중 초대 닫기'}>×</button>
    </div>
    {large && info ? <>
      <img className="invite-qr-image" src={info.image} alt="확대된 청중 참여 QR코드" />
      <p className="invite-qr-caption">참여 코드 <strong>{info.joinCode}</strong></p>
    </> : failed ? <div>
      <p role="alert">참여 정보를 불러오지 못했습니다.</p>
      <button className="room-secondary" onClick={retry}>다시 불러오기</button>
    </div> : !info ? <p role="status">참여 정보를 불러오고 있습니다.</p> : <>
      <div className="invite-code"><span>참여 코드</span><strong>{info.joinCode}</strong></div>
      <label htmlFor="invite-url">참여 링크</label>
      <div className="invite-copy"><input id="invite-url" readOnly value={info.joinUrl} onFocus={event => event.target.select()} />
        <button type="button" onClick={copyLink}>링크 복사</button></div>
      {notice && <p className="invite-notice" role="status">{notice}</p>}
      <button ref={qrButton} type="button" className="invite-qr-button" onClick={() => setLarge(true)}>QR 크게 보기</button>
    </>}
  </dialog>
}
