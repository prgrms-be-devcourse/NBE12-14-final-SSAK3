import React, { useEffect, useRef, useState } from 'react'
import './host-layout.css'

function HostMenu({ mobile = false, section, canInvite, inviteOpen, navigationDisabled, choose }) {
  function navigationItem(target, label) {
    return <button type="button" className="host-menu-item" disabled={navigationDisabled}
      aria-current={section === target ? 'location' : undefined} onClick={() => choose(target)}>
      <i aria-hidden="true" className={`host-glyph host-glyph-${target}`} />{label}
    </button>
  }
  return <nav aria-label="강연자 메뉴" className="host-menu">
    {navigationItem('home', '홈')}
    {navigationItem('new', '새 강연방')}
    {navigationItem('history', '내 강연 기록')}
    {canInvite && <button type="button" className="host-menu-item host-invite-action"
      id={mobile ? undefined : 'host-invite-trigger'} aria-haspopup="dialog" aria-expanded={inviteOpen}
      aria-controls={inviteOpen ? 'host-invite-panel' : undefined} onClick={() => choose('invite')}>
      <i aria-hidden="true" className="host-glyph host-glyph-invite" />청중 초대
    </button>}
  </nav>
}

export function RoomShell({ user, logout, logoutDisabled, onNavigate, section, canInvite,
  inviteOpen, onToggleInvite, navigationDisabled = false, children }) {
  const drawer = useRef(null)
  const [menuOpen, setMenuOpen] = useState(false)

  useEffect(() => {
    const mobile = window.matchMedia('(max-width: 760px)')
    const resized = () => { if (!mobile.matches) drawer.current?.close() }
    mobile.addEventListener('change', resized)
    return () => mobile.removeEventListener('change', resized)
  }, [])

  function choose(target) {
    if (drawer.current?.open) drawer.current.close()
    if (target === 'invite') onToggleInvite()
    else onNavigate(target)
  }
  const menuProps = { section, canInvite, inviteOpen, navigationDisabled, choose }

  return <div className="room-app host-shell">
    <header className="host-bar">
      <button id="host-menu-trigger" type="button" aria-label="메뉴 열기"
        aria-expanded={menuOpen} aria-haspopup="dialog" aria-controls="host-mobile-menu"
        className="host-icon-button" onClick={() => { drawer.current.showModal(); setMenuOpen(true) }}>
        <i aria-hidden="true" className="host-glyph host-glyph-menu" />
      </button>
      <button type="button" className="host-brand" aria-label="RE:ACT 홈"
        disabled={navigationDisabled} onClick={() => choose('home')}>
        <span className="host-brand-mark" aria-hidden="true"><i /><i /><i /><i /></span>RE:ACT
      </button>
      {user && <div className="host-account">
        <span className="host-avatar" aria-hidden="true">{user.name?.trim().slice(0, 1)}</span>
        <span className="host-account-name">{user.name}</span>
        <button className="host-logout" type="button" disabled={logoutDisabled} onClick={logout}>로그아웃</button>
      </div>}
    </header>
    <aside className="host-rail"><HostMenu {...menuProps} /></aside>
    <dialog id="host-mobile-menu" ref={drawer} className="host-mobile-menu" aria-labelledby="host-menu-heading"
      onClose={() => setMenuOpen(false)} onClick={event => {
        if (event.target === event.currentTarget) drawer.current.close()
      }}>
      <div className="host-drawer-content">
        <div className="host-drawer-header"><h2 id="host-menu-heading">메뉴</h2>
          <button type="button" aria-label="메뉴 닫기" className="host-icon-button" onClick={() => drawer.current.close()}>
            <i aria-hidden="true" className="host-glyph host-glyph-close" />
          </button>
        </div>
        <HostMenu {...menuProps} mobile />
      </div>
    </dialog>
    <div className="host-body"><main id="host-main">{children}</main></div>
  </div>
}
