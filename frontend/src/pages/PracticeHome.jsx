import React, { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api, post } from '../api'
import { ErrorNotice } from '../components/Shell'

export default function PracticeHome() {
  const navigate = useNavigate()
  const [categories, setCategories] = useState([])
  const [category, setCategory] = useState('')
  const [progress, setProgress] = useState(null)
  const [current, setCurrent] = useState(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [revision, setRevision] = useState(0)
  const lastSession = sessionStorage.getItem('csmatch.lastSession')
  useEffect(() => {
    let active = true
    setLoading(true); setError('')
    Promise.all([api('/practice/categories'), api('/practice/sessions/current'),
      api(`/practice/progress${category ? `?categoryId=${category}` : ''}`)])
      .then(([topics, session, stats]) => {
        if (active) { setCategories(topics); setCurrent(session); setProgress(stats) }
      }).catch(failure => { if (active) setError(failure.message) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [category, revision])
  async function start() {
    if (busy) return
    setBusy(true); setError('')
    try {
      const session = await post('/practice/sessions', { categoryId: category ? Number(category) : null })
      sessionStorage.setItem('csmatch.lastSession', String(session.sessionId))
      navigate(`/practice/${session.sessionId}`)
    } catch (failure) {
      if (failure.code === 'PRACTICE_IN_PROGRESS') setRevision(value => value + 1)
      setError(failure.code === 'NO_MORE_QUESTIONS' ? '이 분야에 남은 문제가 없어요. 다른 분야를 선택하거나 전체 진도를 초기화해 주세요.' : failure.message)
    } finally { setBusy(false) }
  }
  async function restart() {
    if (busy || !window.confirm('모든 분야의 진도를 초기화할까요? 지난 풀이 기록은 유지돼요.')) return
    setBusy(true); setError('')
    try { await post('/practice/restart'); setRevision(value => value + 1) }
    catch (failure) { setError(failure.message) }
    finally { setBusy(false) }
  }
  return <main className="container practice-container">
    <Link className="back-link" to="/">← 로비로 돌아가기</Link>
    <div className="welcome"><div><p className="eyebrow">PRACTICE MAKES PROGRESS</p><h1>나의 속도로, 한 문제씩.</h1><p>분야를 고르고 연습을 시작하세요. 정답과 해설은 제출 후 확인할 수 있어요.</p></div><span className="preview-badge">시간제한 없음</span></div>
    <ErrorNotice message={error} retry={() => setRevision(value => value + 1)} />
    {loading ? <p className="loading" role="status">학습 진도를 불러오고 있어요…</p> : <>
      {current && <section className="resume-banner"><div><strong>진행 중인 연습이 있어요</strong><p>{current.answeredCount} / {current.questionCount}문제 제출 · 지금 이어서 풀 수 있어요.</p></div><Link className="primary" to={`/practice/${current.sessionId}`}>이어서 풀기 →</Link></section>}
      <section className="practice-setup"><div className="practice-fields"><h2>어떤 분야를 연습할까요?</h2><p className="muted">각 분야의 진도는 전체 학습 진도에 함께 반영돼요.</p><div className="topic-tabs" role="group" aria-label="연습 분야">
        <button className={!category ? 'current' : ''} aria-pressed={!category} onClick={() => setCategory('')} disabled={busy}>전체</button>
        {categories.map(topic => <button key={topic.categoryId} className={category === String(topic.categoryId) ? 'current' : ''} aria-pressed={category === String(topic.categoryId)} disabled={busy} onClick={() => setCategory(String(topic.categoryId))}>{topic.name}</button>)}
      </div><div className="practice-rules"><span>최대 10문제씩</span><span>레이팅 변동 없음</span><span>제출 후 해설 공개</span></div>
      {import.meta.env.MODE === 'demo' && <div className="info-box">이번 데모에는 네트워크 예시 문제 1개가 있어요.<br/>‘전체’ 또는 ‘네트워크’를 선택해 주세요.</div>}
      </div><aside className="practice-start"><span className="eyebrow">YOUR PROGRESS</span><div className="progress-number">{progress?.answeredCount ?? 0}<small> / {progress?.totalCount ?? 0}</small></div><p>현재 분야에서 제출한 문제</p>
        {progress?.totalCount === 0 ? <p className="empty-message">아직 등록된 문제가 없어요.</p> : progress?.completed ? <p className="completion-note">이 분야의 문제를 모두 풀었어요!</p> : <p className="muted">남은 문제 {progress?.remainingCount ?? 0}개</p>}
        <button className="primary" onClick={start} disabled={busy || !!current || !progress?.remainingCount}>{busy ? '처리 중…' : '연습 시작하기 →'}</button>
        {!current && !!progress?.answeredCount && <button className="text-button" onClick={restart} disabled={busy}>전체 진도 초기화</button>}
        {lastSession && <Link className="text-button" to={`/practice/${lastSession}?result=1`}>마지막 연습 확인</Link>}
      </aside></section>
    </>}
  </main>
}
