import React, { useEffect, useRef, useState } from 'react'
import { Brand } from '../ui/Brand.jsx'
import { Icon } from '../ui/Icon.jsx'
import './host-layout.css'

function HostMenu({
                    mobile = false,
                    section,
                    canInvite,
                    inviteOpen,
                    navigationDisabled,
                    choose,
                  }) {
  function navigationItem(target, label, icon) {
    return <button
        type="button"
        className="host-menu-item"
        disabled={navigationDisabled}
        aria-current={section === target ? 'location' : undefined}
        onClick={() => choose(target)}
    >
      <Icon name={icon} size={19} />
      {label}
    </button>
  }

  return <nav aria-label="강연자 메뉴" className="host-menu">
    {navigationItem('home', '홈', 'home')}
    {navigationItem('new', '새 강연방', 'plus')}
    {navigationItem('history', '내 강연 기록', 'history')}

    {canInvite && <button
        type="button"
        className="host-menu-item host-invite-action"
        id={mobile ? undefined : 'host-invite-trigger'}
        aria-haspopup="dialog"
        aria-expanded={inviteOpen}
        aria-controls={inviteOpen ? 'host-invite-panel' : undefined}
        onClick={() => choose('invite')}
    >
      <Icon name="qr" size={19} />
      청중 초대
    </button>}
  </nav>
}

function HostAccount({ user, logout, logoutDisabled }) {
  if (!user) return null

  return <div className="host-rail-account">
    <div className="host-profile">
      <span className="host-avatar" aria-hidden="true">
        {user.name?.trim().slice(0, 1) || '?'}
      </span>

      <span className="host-account-name" title={user.name}>
        {user.name}
      </span>
    </div>

    <button
        className="host-logout"
        type="button"
        disabled={logoutDisabled}
        onClick={logout}
    >
      <Icon name="log-out" size={19} />
      로그아웃
    </button>
  </div>
}

export function RoomShell({
                            user,
                            logout,
                            logoutDisabled,
                            onNavigate,
                            section,
                            canInvite,
                            inviteOpen,
                            onToggleInvite,
                            navigationDisabled = false,
                            liveRoom = false,
                            liveActionsRef,
                            children,
                          }) {
  const drawer = useRef(null)
  const [menuOpen, setMenuOpen] = useState(false)

  useEffect(() => {
    const mobile = window.matchMedia('(max-width: 760px)')

    const resized = () => {
      if (!mobile.matches) drawer.current?.close()
    }

    mobile.addEventListener('change', resized)

    return () => mobile.removeEventListener('change', resized)
  }, [])

  function choose(target) {
    if (drawer.current?.open) drawer.current.close()

    if (target === 'invite') onToggleInvite()
    else onNavigate(target)
  }

  const menuProps = {
    section,
    canInvite,
    inviteOpen,
    navigationDisabled,
    choose,
  }

  const accountProps = {
    user,
    logout,
    logoutDisabled,
  }

  return <div className="room-app host-shell">
    <a className="host-skip" href="#host-main">
      본문으로 이동
    </a>

    <header className="host-bar">
      <button
          id="host-menu-trigger"
          type="button"
          aria-label="메뉴 열기"
          aria-expanded={menuOpen}
          aria-haspopup="dialog"
          aria-controls="host-mobile-menu"
          className="host-icon-button"
          onClick={() => {
            drawer.current.showModal()
            setMenuOpen(true)
          }}
      >
        <Icon name="menu" />
      </button>

      <button
          type="button"
          className="host-brand"
          aria-label="RE:ACT 홈"
          disabled={navigationDisabled}
          onClick={() => choose('home')}
      >
        <Brand />
      </button>

      <span className="host-context">
        강연자 워크스페이스
      </span>
    </header>

    <aside className={`host-rail host-rail-v2${liveRoom ? ' host-rail-live' : ''}`}>
      <HostMenu {...menuProps} />

      <div className="host-rail-bottom">
        {liveRoom && <div
            ref={liveActionsRef}
            className="host-rail-room-actions"
        />}

        <HostAccount {...accountProps} />
      </div>
    </aside>

    <dialog
        id="host-mobile-menu"
        ref={drawer}
        className="host-mobile-menu"
        aria-labelledby="host-menu-heading"
        onClose={() => setMenuOpen(false)}
        onClick={event => {
          if (event.target === event.currentTarget) {
            drawer.current.close()
          }
        }}
    >
      <div className="host-drawer-content">
        <div className="host-drawer-header">
          <h2 id="host-menu-heading">메뉴</h2>

          <button
              type="button"
              aria-label="메뉴 닫기"
              className="host-icon-button"
              onClick={() => drawer.current.close()}
          >
            <Icon name="x" />
          </button>
        </div>

        <HostMenu {...menuProps} mobile />

        <div className="host-drawer-bottom">
          <HostAccount {...accountProps} />
        </div>
      </div>
    </dialog>

    <div className="host-body">
      <main id="host-main" tabIndex={-1}>
        {children}
      </main>
    </div>
  </div>
}