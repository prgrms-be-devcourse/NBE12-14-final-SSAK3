import React, { useState } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { post } from '../api'
import { useAuth } from '../auth'
import { ErrorNotice } from '../components/Shell'

export default function AuthPage({ signup = false }) {
  const { user, login, expired } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [registered, setRegistered] = useState(false)
  const destination = location.state?.from?.startsWith('/practice') ? location.state.from : '/practice'
  if (user) return <Navigate to={destination} replace />

  async function submit(event) {
    event.preventDefault()
    if (busy) return
    const form = new FormData(event.currentTarget)
    const email = form.get('email').trim()
    const password = form.get('password')
    setBusy(true); setError('')
    try {
      if (signup && !registered) {
        await post('/auth/signup', { email, password, nickname: form.get('nickname').trim() })
        setRegistered(true)
      }
      const result = await post('/auth/login', { email, password })
      login(result.accessToken, email)
      navigate(destination, { replace: true })
    } catch (failure) { setError(failure.message) }
    finally { setBusy(false) }
  }
  return <main className="container auth-layout">
    <section className="auth-intro"><p className="eyebrow">A LITTLE CHALLENGE. A BIG STEP.</p><h1>한 문제부터,<br/>꾸준히 쌓는 CS 실력.</h1><p>나만의 속도로 문제를 풀고<br/>정답과 해설로 이해를 넓혀보세요.</p><div className="auth-art" aria-hidden="true">&lt;/&gt;</div></section>
    <section className="auth-card"><span className="hero-label">CS MATCH</span><h2>{signup ? '회원가입' : '다시 만나 반가워요'}</h2><p>{signup ? '나의 학습 기록을 남길 계정을 만들어 주세요.' : '로그인하고 지난 연습을 이어가세요.'}</p>
      {expired && <p className="info-box" role="status">로그인이 만료됐어요. 다시 로그인하면 이어서 풀 수 있어요.</p>}
      {registered && <p className="info-box" role="status">가입은 완료됐어요. 로그인 정보를 확인하고 다시 시도해 주세요.</p>}
      <ErrorNotice message={error} />
      <form onSubmit={submit}>
        {signup && <label>닉네임<input name="nickname" required maxLength={30} autoComplete="nickname" placeholder="사용할 닉네임" disabled={registered || busy} /></label>}
        <label>이메일<input name="email" type="email" required maxLength={255} autoComplete="username" placeholder="you@example.com" disabled={busy} /></label>
        <label>비밀번호<input name="password" type="password" required minLength={signup ? 8 : undefined} maxLength={72} autoComplete={signup ? 'new-password' : 'current-password'} placeholder="비밀번호를 입력하세요" disabled={busy} /></label>
        {signup && <small>비밀번호는 8~72자로 입력해 주세요.</small>}
        <button className="primary" disabled={busy}>{busy ? '처리 중…' : signup && !registered ? '가입하고 연습 시작하기' : '로그인'}</button>
      </form>
      <p className="auth-switch">{signup ? '이미 계정이 있나요?' : '처음 방문했나요?'} <Link state={location.state} to={signup ? '/login' : '/signup'}>{signup ? '로그인' : '회원가입'}</Link></p>
      {import.meta.env.MODE === 'demo' && <p className="muted">데모 전용 계정이에요. 평소 사용하는 비밀번호는 입력하지 마세요.</p>}
    </section>
  </main>
}
