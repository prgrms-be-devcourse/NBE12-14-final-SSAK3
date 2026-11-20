import { useEffect, useRef, useState } from 'react'

const pending = status => status === 'PENDING' || status === 'PROCESSING'

// Mount a new instance for each room/source. Future adapters must verify ownership on the server.
export function useReport(source) {
  const [state, setState] = useState({ status: 'LOADING', content: null, busy: false, error: null })
  const control = useRef(null)
  useEffect(() => {
    let alive = true, running = false, timer = null, status = 'LOADING', halted = false
    const cancel = () => { clearTimeout(timer); timer = null }
    const schedule = () => {
      cancel()
      if (alive && !halted && !document.hidden && pending(status)) timer = setTimeout(() => execute(false), 5000)
    }
    async function execute(create) {
      if (!alive || running) return
      running = true
      cancel()
      setState(s => ({ ...s, busy: true, error: null }))
      try {
        const value = await (create ? source.create() : source.load())
        if (!alive) return
        status = value.status
        halted = false
        setState({ ...value, error: null, busy: false })
      } catch (error) {
        if (!alive) return
        halted = true
        const denied = error.status === 401 || error.status === 403
        if (denied) status = 'ACCESS_DENIED'
        setState(s => ({ ...s, status: denied ? 'ACCESS_DENIED' : s.status,
          content: denied ? null : s.content, busy: false,
          error: denied ? '로그인 상태와 강연방 접근 권한을 확인해 주세요.'
            : create ? '요청 결과를 확인하지 못했습니다. 상태 확인으로 생성 여부를 확인해 주세요.'
              : '보고서 상태를 조회하지 못했습니다. 연결을 확인한 뒤 상태 확인을 눌러 주세요.' }))
      } finally { running = false; schedule() }
    }
    const visible = () => {
      cancel()
      if (!document.hidden && !halted && pending(status)) execute(false)
    }
    control.current = {
      check: () => execute(false),
      create: () => { if (status === 'NOT_CREATED' && !halted) execute(true) },
    }
    document.addEventListener('visibilitychange', visible)
    // React StrictMode cleans up its first effect before this task: avoid a duplicate initial read.
    queueMicrotask(() => execute(false))
    return () => { alive = false; cancel(); document.removeEventListener('visibilitychange', visible); control.current = null }
  }, [source])
  return { ...state, check: () => control.current?.check(), create: () => control.current?.create() }
}
