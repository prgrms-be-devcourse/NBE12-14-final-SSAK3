import React, { useEffect, useRef, useState, useSyncExternalStore } from 'react'

export function LeaveRoomDialog({ recorder, intent, onStay, onReady }) {
  const [waiting, setWaiting] = useState(!intent.confirm)
  const dialog = useRef(null)
  const finished = useRef(false)
  const safe = useSyncExternalStore(recorder.subscribe, () => recorder.getSnapshot().navigationSafe)
  const destination = { home: '홈', new: '새 강연방', history: '내 강연 기록' }[intent.target]

  useEffect(() => {
    const element = dialog.current
    element.showModal()
    return () => element.close()
  }, [])
  useEffect(() => {
    if (!waiting || !safe || finished.current) return
    finished.current = true
    onReady(intent.target)
  }, [waiting, safe, intent.target, onReady])

  function stay() {
    finished.current = true
    onStay()
  }
  function confirm() {
    recorder.prepareForNavigation()
    setWaiting(true)
  }
  return <dialog ref={dialog} className="room-dialog host-nav-dialog" aria-labelledby="host-leave-title"
    aria-describedby="host-leave-description" onCancel={event => { event.preventDefault(); stay() }}>
    <h2 id="host-leave-title">{waiting ? '마지막 발언 저장 확인 중' : '녹음 중인 강연에서 나갈까요?'}</h2>
    <p id="host-leave-description" role={waiting ? 'status' : undefined}>{waiting
      ? `브라우저나 서버에 마지막 발언이 저장되면 ${destination}으로 이동합니다.`
      : '강연은 계속되지만 이 화면을 떠나면 발언 기록이 멈춥니다. 돌아온 뒤 녹음 재개를 눌러 이어갈 수 있어요.'}</p>
    <div>{waiting ? <>
      <button type="button" className="room-secondary" onClick={stay}>강연에 머무르기</button>
      <button type="button" onClick={() => recorder.retryNavigationSave()}>저장 재시도</button>
    </> : <>
      <button type="button" autoFocus className="room-secondary" onClick={stay}>계속 녹음하기</button>
      <button type="button" onClick={confirm}>이동하기</button>
    </>}</div>
  </dialog>
}
