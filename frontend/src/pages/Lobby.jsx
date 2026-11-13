import React from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../auth'

export default function Lobby() {
  const { user } = useAuth()
  return <main className="container">
    <div className="welcome"><div><p className="eyebrow">LET’S MATCH, LET’S GROW</p><h1>오늘도 한 문제 더, 한 걸음 더.</h1><p>차근차근 쌓아가는 나의 CS 실력</p></div>{import.meta.env.MODE === 'demo' && <span className="preview-badge"><i />연습 데모</span>}</div>
    <div className="hero-grid"><section className="hero">
      <div className="hero-copy"><span className="hero-label">개발자를 위한 CS 학습</span><h2>알고 있는 지식이<br/>실력이 되는 순간.</h2><p>한 문제씩, 나만의 속도로.<br/>정답과 해설로 이해를 완성하세요.</p><Link to="/practice" className="hero-link">지금 연습 시작하기 <span>↗</span></Link></div>
      <div className="hero-art" aria-hidden="true"><div className="orbit orbit-one"/><div className="orbit orbit-two"/><span className="spark spark-one">+</span><span className="spark spark-two">+</span><div className="code-tile tile-back"><span>KEEP LEARNING</span><strong>{'{ }'}</strong><i>ONE STEP AT A TIME</i></div><div className="code-tile tile-front"><span>YOUR NEXT CHALLENGE</span><strong>&lt;/&gt;</strong><i><b/> READY TO LEARN</i></div><span className="art-caption">A LITTLE CHALLENGE. A BIG STEP.</span></div>
      <div className="hero-bottom"><span>최대 10문제씩</span><i/><span>시간제한 없음</span><i/><span>레이팅 미반영</span></div>
    </section><aside className="profile"><div className="profile-heading"><h2>나의 학습 공간</h2><span>연습 모드</span></div><div className="profile-person"><span className="avatar large">C</span><div><strong>{user ? '오늘도 함께 공부해요' : '첫 도전을 시작해 볼까요?'}</strong><p>{user ? '풀이 기록이 안전하게 저장돼요.' : '로그인하고 나만의 진도를 남겨요.'}</p></div></div><div className="learning-points"><p>✓ 새로고침해도 이어서</p><p>✓ 문제를 풀면 바로 해설 확인</p><p>✓ 틀려도 부담 없는 연습</p></div><Link className="profile-link" to={user ? '/practice' : '/login'}>{user ? '내 연습으로 이동' : '로그인하기'} <span>→</span></Link><button className="prepared-button" disabled>프로필 상세 · 준비 중</button></aside></div>
    <section className="match-section" id="match"><div className="section-title"><div><h2>어떤 도전을 시작할까요?</h2><p>지금은 연습 모드부터 만나볼 수 있어요.</p></div><span className="section-note">CS 객관식으로 차근차근</span></div><div className="lobby-modes">
      <Link className="mode-card selected" to="/practice"><span className="mode-icon blue">✎</span><span className="mode-copy"><strong>연습 모드 <em>지금 시작</em></strong><span>혼자서 천천히 풀고<br/>정답과 해설을 확인해요.</span><small>시간제한 없이 나만의 속도로</small></span><span>→</span></Link>
      <button className="mode-card" disabled><span className="mode-icon blue">♜</span><span className="mode-copy"><strong>레이팅 매치</strong><span>비슷한 실력의 상대와 겨루는 대결</span><small>준비 중</small></span></button>
      <button className="mode-card" disabled><span className="mode-icon green">⚑</span><span className="mode-copy"><strong>일반 매치</strong><span>점수 부담 없이 즐기는 대결</span><small>준비 중</small></span></button>
    </div></section>
    <section className="review-banner"><div className="book-icon" aria-hidden="true">▤</div><div><h2>틀렸던 문제도, 내 실력으로.</h2><p>지금은 연습 결과에서 정답과 해설을 확인하세요.</p></div><button disabled>내 복습 · 준비 중</button></section>
    <section className="guide"><h2>처음이라면, 이것만 기억하세요.</h2><div><article><span>01</span><h3>분야를 골라 연습</h3><p>전체 또는 관심 있는 분야를 선택하세요.</p></article><article><span>02</span><h3>제출하면 바로 해설</h3><p>한 문제에 한 번, 충분히 생각하고 제출하세요.</p></article><article><span>03</span><h3>다시 풀며 익히기</h3><p>완료 후 다시 풀기로 한 번 더 확인하세요.</p></article></div></section>
  </main>
}
