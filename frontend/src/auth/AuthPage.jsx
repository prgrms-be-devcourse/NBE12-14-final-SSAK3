import React, { useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { request } from '../stt/api.js'
import { returnPath } from '../room/state.js'
import { validateAuth } from './validation.js'
import './auth.css'

export function AuthPage() {
  const location = useLocation()
  const signup = location.pathname === '/signup'
  // Each form owns its credentials only in memory; navigating between forms discards them.
  return <AuthForm key={location.key} signup={signup} location={location} />
}

function AuthForm({ signup, location }) {
  const navigate = useNavigate()
  const returnTo = returnPath(new URLSearchParams(location.search).get('returnTo'))
  const target = returnTo || '/host/rooms/new'
  const suffix = returnTo ? `?returnTo=${encodeURIComponent(returnTo)}` : ''
  const [values, setValues] = useState({ name: '', email: location.state?.email || '', password: '', confirm: '' })
  const [touched, setTouched] = useState({})
  const [checking, setChecking] = useState(true)
  const [checkError, setCheckError] = useState(false)
  const [revision, setRevision] = useState(0)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [duplicate, setDuplicate] = useState(false)
  const [uncertain, setUncertain] = useState(false)
  const lock = useRef(false)
  const fields = useRef({})
  useEffect(() => {
    let active = true
    request('/auth/me').then(() => { if (active) navigate(target, { replace: true }) }).catch(e => {
      if (!active) return
      if (e.status !== 401) setCheckError(true)
      setChecking(false)
    })
    return () => { active = false }
  }, [revision, navigate, target])
  const validation = validateAuth(values, signup)
  function change(name, value) {
    setValues(current => ({ ...current, [name]: value }))
    if (name === 'email') setDuplicate(false)
    setError('')
  }
  function loginLink(success = false) {
    navigate(`/login${suffix}`, { replace: success, state: { email: values.email.trim().toLowerCase(), signupSuccess: success } })
  }
  async function submit(event) {
    event.preventDefault()
    if (lock.current) return
    const names = signup ? ['name', 'email', 'password', 'confirm'] : ['email', 'password']
    setTouched(Object.fromEntries(names.map(name => [name, true])))
    const first = names.find(name => validation[name])
    if (first) { fields.current[first]?.focus(); return }
    lock.current = true; setBusy(true); setError(''); setDuplicate(false)
    try {
      const body = { email: values.email.trim(), password: values.password }
      if (signup) body.name = values.name.trim()
      await request(signup ? '/auth/signup' : '/auth/login', { method: 'POST', body })
      if (signup) loginLink(true)
      else {
        await request('/auth/me')
        navigate(target, { replace: true })
      }
    } catch (e) {
      if (signup && e.code === 'EMAIL_ALREADY_EXISTS') {
        setDuplicate(true); fields.current.email?.focus()
      } else if (!signup && (e.code === 'INVALID_CREDENTIALS' || e.status === 401)) {
        setError('이메일 또는 비밀번호를 확인해 주세요.')
      } else if (e.status === 400) setError('입력 내용을 확인해 주세요.')
      else if (e.status === 429) setError('요청이 많습니다. 잠시 후 다시 시도해 주세요.')
      else if (e.status === 403) setError('요청을 처리할 수 없습니다. 다시 시도해 주세요.')
      else if (signup) setUncertain(true)
      else setError('로그인 결과를 확인하지 못했습니다. 연결 상태를 확인하고 다시 시도해 주세요.')
    } finally { lock.current = false; setBusy(false) }
  }
  function input(name, label, type, autoComplete, maxLength) {
    const message = name === 'email' && duplicate ? '이미 가입된 이메일입니다.' : touched[name] ? validation[name] : ''
    return <div className="auth-field"><label htmlFor={`auth-${name}`}>{label}</label>
      <input id={`auth-${name}`} name={name} ref={element => { fields.current[name] = element }} type={type} autoComplete={autoComplete}
        value={values[name]} maxLength={maxLength} disabled={busy} required aria-invalid={!!message} aria-describedby={`auth-${name}-error`}
        onBlur={() => setTouched(current => ({ ...current, [name]: true }))} onChange={e => change(name, e.target.value)} />
      <p id={`auth-${name}-error`} className="auth-field-error" aria-live="polite">{message || '\u00a0'}</p>
    </div>
  }
  return <div className="room-app auth-app">
    <header className="room-top"><a href="/login">RE:ACT<span>강연자 공간</span></a></header>
    <main className="auth-main"><div className="room-heading"><span className="room-kicker">청중과 함께 만드는 강연</span>
      <h1>{signup ? '강연자로 시작하기' : '다시 만나 반가워요'}</h1><p>{signup ? '계정을 만들고 첫 강연을 준비하세요.' : '로그인하고 강연을 이어가세요.'}</p></div>
      <section className="room-card">
        {checking ? <p role="status">로그인 상태를 확인하고 있습니다.</p> : checkError ? <>
          <p role="alert">로그인 상태를 확인하지 못했습니다. 서버 연결을 확인해 주세요.</p>
          <button onClick={() => { setCheckError(false); setChecking(true); setRevision(v => v + 1) }}>다시 확인</button>
        </> : <form noValidate onSubmit={submit}>
          {!signup && location.state?.signupSuccess && <p className="room-note" role="status">가입이 완료되었습니다. 로그인해 주세요.</p>}
          {signup && input('name', '이름', 'text', 'name', 100)}
          {input('email', '이메일', 'email', 'username', 255)}
          {input('password', '비밀번호', 'password', signup ? 'new-password' : 'current-password')}
          {signup && <><p className="auth-password-hint">8자 이상, 72바이트 이하 · 한글 등은 글자 수와 다를 수 있습니다.</p>{input('confirm', '비밀번호 확인', 'password', 'new-password')}</>}
          {error && <p role="alert" className="auth-field-error">{error}</p>}
          {duplicate && <button type="button" className="auth-text-button" onClick={() => loginLink()}>이 이메일로 로그인</button>}
          {uncertain && <div className="room-warning" role="alert"><strong>가입 여부를 확인하지 못했습니다.</strong><p>이미 계정이 만들어졌을 수 있습니다. 로그인으로 확인하거나 가입을 다시 시도해 주세요.</p><button type="button" className="room-secondary" disabled={busy} onClick={() => loginLink()}>로그인으로 확인</button></div>}
          <button className="auth-submit" disabled={busy}>{busy ? '처리 중…' : signup ? '회원가입' : '로그인'}</button>
          <p className="auth-switch">{signup ? '이미 계정이 있나요?' : '아직 계정이 없나요?'}{' '}
            <button type="button" className="auth-text-button" disabled={busy} onClick={() => signup ? loginLink() : navigate(`/signup${suffix}`)}>{signup ? '로그인' : '회원가입'}</button></p>
        </form>}
      </section>
    </main><footer className="room-footer">RE:ACT · 청중의 생각이 강연의 일부가 되도록</footer>
  </div>
}
