import { useEffect, useRef, useState } from 'react'

const pending = status => status === 'PENDING' || status === 'PROCESSING'

// Each source belongs to one room and signed-in account.
export function useReport(source) {
  const [state, setState] = useState({ status: 'LOADING', content: null, busy: false, error: null })
  const control = useRef(null)
  useEffect(() => {
    let alive = true, running = false, timer = null, status = 'LOADING', halted = false
    let pollDelay = null, retryAvailable = false
    const cancel = () => { clearTimeout(timer); timer = null }
    const schedule = () => {
      cancel()
      if (alive && !halted && !document.hidden && pollDelay !== null) {
        timer = setTimeout(() => execute('load'), pollDelay)
      }
    }
    async function execute(action) {
      if (!alive || running) return
      running = true
      cancel()
      setState(s => ({ ...s, busy: true, error: null }))
      try {
        const value = await source[action]()
        if (!alive) return
        status = value.status
        retryAvailable = value.retryAvailable === true
        pollDelay = value.checkAfterMs ?? (pending(status) ? 5000 : null)
        halted = false
        setState({ ...value, error: null, busy: false })
      } catch (error) {
        if (!alive) return
        halted = true
        const denied = error.status === 401 || error.status === 403
        if (denied || (error.status === 404 && error.code === 'ROOM_NOT_FOUND')) {
          source.onAccessError?.(error)
        }
        if (denied) status = 'ACCESS_DENIED'
        setState(s => ({ ...s, status: denied ? 'ACCESS_DENIED' : s.status,
          content: denied ? null : s.content, busy: false,
          error: denied ? '로그인 상태와 강연방 접근 권한을 확인해 주세요.'
            : action !== 'load' ? '요청 결과를 확인하지 못했습니다. 보고서 상태를 다시 확인해 주세요.'
              : '보고서 상태를 조회하지 못했습니다. 연결을 확인한 뒤 다시 시도해 주세요.' }))
      } finally { running = false; schedule() }
    }
    const visible = () => {
      cancel()
      if (!document.hidden && !halted && (status === 'LOADING' || pollDelay !== null)) execute('load')
    }
    control.current = {
      check: () => execute('load'),
      create: () => { if (status === 'NOT_CREATED' && !halted) execute('create') },
      retry: () => { if (status === 'FAILED' && retryAvailable && !halted) execute('retry') },
    }
    document.addEventListener('visibilitychange', visible)
    // React StrictMode cleans up its first effect before this task: avoid a duplicate initial read.
    queueMicrotask(visible)
    return () => { alive = false; cancel(); document.removeEventListener('visibilitychange', visible); control.current = null }
  }, [source])
  return { ...state, check: () => control.current?.check(), create: () => control.current?.create(),
    retry: () => control.current?.retry() }
}
