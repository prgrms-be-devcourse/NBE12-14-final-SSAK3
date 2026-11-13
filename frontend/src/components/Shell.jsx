import React from 'react'
import { Link, NavLink, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth'

export function Brand() {
  return <Link className="brand" to="/"><span className="brand-symbol">C<span>›</span></span>CS매치<span className="beta">BETA</span></Link>
}
export function ErrorNotice({ message, retry }) {
  return message ? <div className="error-notice" role="alert"><span>{message}</span>{retry && <button onClick={retry}>다시 시도</button>}</div> : null
}
export function Shell({ children }) {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  return <>
    <header className="header"><div className="header-inner"><Brand />
      <nav aria-label="주 메뉴"><NavLink to="/" end>대결 로비</NavLink><NavLink to="/practice">연습 모드</NavLink>
        <button disabled title="준비 중">내 복습 <small className="coming">준비 중</small></button></nav>
      <div className="header-user">{user ? <><span className="avatar">C</span><span className="account-email">{user.email}</span><button onClick={() => { logout(); navigate('/login') }}>로그아웃</button></> : <Link to="/login">로그인 / 회원가입</Link>}</div>
    </div></header>
    {children}
    <footer><div className="container"><Brand /><p>작은 도전이 만드는 단단한 개발 지식.</p><span>© CS MATCH{import.meta.env.MODE === 'demo' ? ' · 로컬 예시 데이터로 학습 중' : ''}</span></div></footer>
  </>
}
