import React, { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { api, post } from '../api'
import { ErrorNotice } from '../components/Shell'

export default function PracticeSession() {
  const { sessionId } = useParams()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const [state, setState] = useState(null)
  const [selected, setSelected] = useState(null)
  const [showAnswer, setShowAnswer] = useState(true)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const requestNumber = useRef(0)
  const resultView = params.get('result') === '1'
  const validId = /^[1-9]\d*$/.test(sessionId)
  const load = useCallback(async () => {
    const request = ++requestNumber.current
    setLoading(true)
    try {
      if (!validId) throw new Error('올바른 연습 주소가 아니에요.')
      let summary = await api(`/practice/sessions/${sessionId}`)
      let next = null
      if (summary.status === 'IN_PROGRESS') {
        try { next = await api(`/practice/sessions/${sessionId}/question`) }
        catch (failure) { if (failure.code !== 'NO_MORE_QUESTIONS') throw failure }
      }
      const history = await api(`/practice/sessions/${sessionId}/questions`)
      summary = await api(`/practice/sessions/${sessionId}`)
      if (request === requestNumber.current) {
        setState({ summary, history, next }); setSelected(null); setShowAnswer(true)
        sessionStorage.setItem('csmatch.lastSession', sessionId)
      }
    } catch (failure) { if (request === requestNumber.current) setError(failure.message) }
    finally { if (request === requestNumber.current) setLoading(false) }
  }, [sessionId, validId])
  useEffect(() => {
    setState(null); setError(''); load()
    return () => { requestNumber.current++ }
  }, [load])
  useEffect(() => {
    const focus = () => { if (!busy) load() }
    window.addEventListener('focus', focus)
    return () => window.removeEventListener('focus', focus)
  }, [load, busy])
  async function submit() {
    if (busy || selected === null || !state?.next) return
    setBusy(true); setError('')
    try {
      await post(`/practice/sessions/${sessionId}/answers`, { questionId: state.next.question.questionId, choiceId: selected })
      await load()
    } catch (failure) {
      setError(['ALREADY_ANSWERED', 'SESSION_FINISHED'].includes(failure.code) ? '다른 화면에서 이미 처리된 답안이에요. 최신 기록을 표시했어요.' : failure.message)
      // 응답 유실 시 서버 이력으로 제출 여부를 확인하고 자동 재제출하지 않는다.
      await load()
    } finally { setBusy(false) }
  }
  async function end() {
    if (busy || !window.confirm('지금 연습을 종료할까요? 제출한 답은 저장되고, 안 푼 문제는 다음에 풀 수 있어요.')) return
    setBusy(true); setError('')
    try { await post(`/practice/sessions/${sessionId}/end`); await load(); navigate(`/practice/${sessionId}?result=1`) }
    catch (failure) { setError(failure.message) }
    finally { setBusy(false) }
  }
  async function restart() {
    if (busy || !window.confirm('전체 분야의 진도를 초기화하고 다시 풀까요? 지난 기록은 유지돼요.')) return
    setBusy(true); setError('')
    try {
      await post('/practice/restart')
      const session = await post('/practice/sessions', { categoryId: state.summary.categoryId })
      navigate(`/practice/${session.sessionId}`)
    } catch (failure) { setError(failure.message) }
    finally { setBusy(false) }
  }
  const answered = state?.history.filter(item => item.status === 'ANSWERED') || []
  const lastAnswer = answered.at(-1)
  const finished = state && state.summary.status !== 'IN_PROGRESS'
  const reveal = showAnswer && lastAnswer
  const current = reveal ? lastAnswer : state?.next
  return <main className="container practice-container">
    <div className="practice-top"><Link className="back-link" to="/practice">← 연습 홈</Link><span className="preview-badge">연습 모드 · 레이팅 미반영</span></div>
    <ErrorNotice message={error} retry={() => { setError(''); load() }} />
    {loading ? <p className="loading" role="status">연습 기록을 불러오고 있어요…</p> : state && <>
      {(resultView && finished) || (finished && !lastAnswer) ? <section className="result-card">
        <div className="result-icon" aria-hidden="true">✓</div><p className="eyebrow">ONE STEP FORWARD</p><h1>{state.summary.status === 'ABANDONED' ? '연습을 종료했어요' : '오늘의 연습을 마쳤어요!'}</h1><p className="muted">맞힌 문제도, 틀린 문제도 나의 실력이 되는 과정이에요.</p>
        <div className="result-stats"><div><strong>{state.summary.correctCount}</strong><span>정답</span></div><div><strong>{state.summary.answeredCount - state.summary.correctCount}</strong><span>오답</span></div><div><strong>{state.summary.questionCount - state.summary.answeredCount}</strong><span>미제출</span></div></div>
        <div className="result-actions"><button className="primary" disabled={busy} onClick={restart}>{busy ? '처리 중…' : '다시 풀기'}</button><Link className="secondary-button" to="/practice">분야 선택으로</Link></div>
        {lastAnswer && <button className="text-button" onClick={() => { setShowAnswer(true); navigate(`/practice/${sessionId}`) }}>정답과 해설 다시 보기</button>}
      </section> : current ? <section className="quiz-layout"><article className="quiz-card">
        <div className="quiz-heading"><span className="question-category">{current.question.categoryId === 1 ? '네트워크' : 'CS 연습'} · {{ EASY: '쉬움', MEDIUM: '보통', HARD: '어려움' }[current.question.difficulty]}</span><span>{reveal ? state.summary.answeredCount : current.index} / {state.summary.questionCount}</span></div>
        <div className="quiz-progress" aria-label={`${state.summary.answeredCount}문제 제출`}><span style={{ width: `${state.summary.questionCount ? state.summary.answeredCount / state.summary.questionCount * 100 : 0}%` }} /></div>
        <h1>{current.question.content}</h1><p className="muted">{reveal ? '제출한 답안이에요. 해설을 확인해 보세요.' : '정답이라고 생각하는 보기 하나를 선택해 주세요.'}</p>
        <div className="answers" role="group" aria-label="답안 보기">{current.choices.map(choice => <button key={choice.choiceId} disabled={busy || !!reveal} aria-pressed={reveal ? choice.choiceId === lastAnswer.selectedChoiceId : selected === choice.choiceId}
          className={reveal ? choice.choiceId === lastAnswer.correctChoiceId ? 'correct' : choice.choiceId === lastAnswer.selectedChoiceId ? 'incorrect' : '' : selected === choice.choiceId ? 'selected-answer' : ''}
          onClick={() => setSelected(choice.choiceId)}><span>{choice.choiceNo}</span><b>{choice.content}</b>{reveal && choice.choiceId === lastAnswer.correctChoiceId && <small>정답</small>}{reveal && choice.choiceId === lastAnswer.selectedChoiceId && <small>내 선택</small>}</button>)}</div>
        {reveal ? <><div className={`answer-result ${lastAnswer.correct ? '' : 'wrong-result'}`} role="status"><strong>{lastAnswer.correct ? '정답이에요!' : '아쉽지만, 하나 더 배웠어요.'}</strong><p>{lastAnswer.explanation}</p>{lastAnswer.hidden && <small>현재 숨김 처리된 문제의 과거 풀이 기록입니다.</small>}</div>
          <button className="primary quiz-submit" disabled={busy} onClick={() => finished ? navigate(`/practice/${sessionId}?result=1`) : setShowAnswer(false)}>{finished ? '결과 확인하기 →' : '다음 문제 →'}</button></> : <button className="primary quiz-submit" disabled={selected === null || busy} onClick={submit}>{busy ? '제출 중…' : '답안 제출'}</button>}
      </article><aside className="quiz-side"><span className="eyebrow">MY PRACTICE</span><h2>천천히 생각해도 괜찮아요.</h2><p>시간제한은 없어요.<br/>답안을 제출하면 정답과 해설을 바로 확인할 수 있어요.</p><div className="side-stats"><span>제출한 문제</span><strong>{state.summary.answeredCount} / {state.summary.questionCount}</strong></div><div className="side-stats"><span>맞힌 문제</span><strong>{state.summary.correctCount}</strong></div>{!finished && <button className="text-button" disabled={busy} onClick={end}>연습 종료</button>}<small>학습 기록은 서버에 저장돼요.</small></aside></section> : <div className="info-box">표시할 문제가 없어요. <Link to="/practice">연습 홈으로 돌아가기</Link></div>}
    </>}
  </main>
}
