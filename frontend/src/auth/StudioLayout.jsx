import React from 'react'
import { Link, useLocation } from 'react-router-dom'
import { Brand } from '../ui/Brand.jsx'
import { Icon } from '../ui/Icon.jsx'
import './auth.css'

const features = [
  ['message', '실시간 소통', '청중의 질문을 한곳에서 확인하세요.'],
  ['chart', '투표와 이해도 체크', '청중이 어떻게 이해했는지 살펴보세요.'],
  ['file', 'AI 회고 보고서', '강연을 마치면 주요 반응을 돌아보세요.'],
]

export function StudioLayout({ audience = false, children }) {
  const location = useLocation()
  return <div className="auth-app">
    <header className="studio-header"><div className="studio-header-inner">
      <Link to="/" className="studio-logo" aria-label="RE:ACT 홈"><Brand /></Link>
      <span className="studio-descriptor">라이브 강연 스튜디오</span>
      <Link className="studio-preview" to="/preview/reports">
        <Icon name="file" size={16} />보고서 미리보기<Icon name="arrow-up-right" size={14} />
      </Link>
    </div></header>
    <main className="studio-main">
      <div className="studio-spread">
        <section className="studio-cover" aria-label="RE:ACT 강연 스튜디오">
          <div className="studio-cover-copy">
            <div className="studio-eyebrow"><Icon name="radio" size={17} /> LIVE LECTURE STUDIO</div>
            <h2>강연을 열고,<br /><em>반응을 듣다.</em></h2>
            <p>청중의 질문과 반응이 모이는<br />실시간 강연 워크스페이스</p>
          </div>
        </section>
        <section className="studio-access" aria-label="스튜디오 입장">
          <div className="studio-access-inner">
            <nav className="studio-mode" aria-label="입장 방식">
              <Link to={'/login' + location.search} aria-current={!audience ? 'page' : undefined}><Icon name="mic" size={16} />강연자</Link>
              <Link to={'/join' + location.search} aria-current={audience ? 'page' : undefined}><Icon name="users" size={17} />청중</Link>
            </nav>
            {children}
          </div>
        </section>
      </div>
      <section className="studio-features" aria-label="스튜디오 주요 기능">
        {features.map(([icon, title, hint]) => <div className="studio-feature" key={icon}>
          <span className="studio-feature-icon"><Icon name={icon} size={23} /></span>
          <div><h3>{title}</h3><p>{hint}</p></div>
        </div>)}
      </section>
    </main>
    <footer className="studio-footer"><span>RE:ACT</span><span>실시간 강연 스튜디오</span></footer>
  </div>
}
