import React, { useEffect, useId, useImperativeHandle, useLayoutEffect, useRef, useState } from 'react'
import { randomUuid } from '../uuid.js'
import { mergeMessages } from './history.js'
import { openRoomChat } from './roomChat.js'
import './chat.css'

const time = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
const uncertainText = '전송 여부를 확인하지 못했어요. 채팅 내역을 확인해 주세요'
const rejection = {
  INVALID_INPUT: '메시지를 1자 이상 1,000자 이내로 입력해 주세요.',
  ROOM_NOT_LIVE: '진행 중인 강연에서 메시지를 보낼 수 있어요.',
  ROOM_ENDED: '강연이 종료되어 메시지를 보낼 수 없어요.',
}

const avatarColors = [
  ['#e4f4ef', '#00766c'],
  ['#eeeafb', '#7460aa'],
  ['#e7effb', '#4676b4'],
  ['#f9eedc', '#95651d'],
  ['#f9e8ee', '#ae5975'],
  ['#e8f2e2', '#558245'],
]

function ChatAvatar({ sender }) {
  const key = `${sender.type}:${sender.id}`
  let hash = 0

  for (const character of key) {
    hash = (Math.imul(31, hash) + character.codePointAt(0)) >>> 0
  }

  const [background, color] = sender.type === 'HOST'
      ? ['#008780', '#ffffff']
      : avatarColors[hash % avatarColors.length]

  return <span
      className="rl-chat-avatar"
      style={{ background, color }}
      aria-hidden="true"
  >
    {Array.from(sender.name?.trim() || '청')[0]}
  </span>
}

function SendIcon() {
  return <svg
      width="21"
      height="21"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
  >
    <path d="m22 2-7 20-4-9-9-4Z" />
    <path d="M22 2 11 13" />
  </svg>
}

function loadAttempt(key) {
  try {
    const value = JSON.parse(sessionStorage.getItem(key))
    if (typeof value?.token === 'string' && typeof value?.content === 'string') return { ...value, uncertain: true }
  } catch { /* In-memory retry still works if tab storage is unavailable. */ }
  return null
}

function storeAttempt(key, attempt) {
  try {
    if (attempt) sessionStorage.setItem(key, JSON.stringify({ token: attempt.token, content: attempt.content }))
    else sessionStorage.removeItem(key)
  } catch { /* Keep the current attempt in memory. */ }
}

export function ChatPanel(props) {
  const { roomId, viewer } = props
  const scope = `react:chat:pending:${roomId}:${viewer.type}:${viewer.id}`
  return <RoomChatPanel key={scope} storageKey={scope} {...props} />
}

function RoomChatPanel({ roomId, live, viewer, since, onAccessError, storageKey, visible = true, rememberPositionRef, showAvatars = false }) {
  const [messages, setMessages] = useState([])
  const [connected, setConnected] = useState(false)
  const [history, setHistory] = useState('loading')
  const [sendError, setSendError] = useState('')
  const [closed, setClosed] = useState(false)
  const [denied, setDenied] = useState(false)
  const [attempt, setAttempt] = useState(() => live ? loadAttempt(storageKey) : null)
  const [draft, setDraft] = useState(attempt?.content ?? '')
  const channel = useRef(null)
  const pending = useRef(attempt)
  const deadline = useRef(null)
  const accessHandler = useRef(onAccessError)
  const composerId = useId()
  const role = viewer.type, viewerId = viewer.id

  const enhanced = showAvatars && live && role === 'HOST'

  useLayoutEffect(() => { accessHandler.current = onAccessError }, [onAccessError])

  function mark(next) {
    clearTimeout(deadline.current)
    pending.current = next
    storeAttempt(storageKey, next)
    setAttempt(next)
  }

  function unknown() {
    if (pending.current && !pending.current.uncertain) mark({ ...pending.current, uncertain: true })
  }

  useEffect(() => {
    if (!live) { mark(null); setDraft(''); setSendError('') }
    setConnected(false)
    const chat = openRoomChat({
      roomId, live, role, since,
      onMessages: batch => setMessages(current => mergeMessages(current, batch)),
      onConnection: setConnected,
      onHistory: setHistory,
      onLost: unknown,
      onAck(token, message) {
        if (!token || token !== pending.current?.token || message.sender.type !== role || message.sender.id !== viewerId) return
        mark(null)
        setDraft('')
        setSendError('')
      },
      isCurrentAttempt: token => token === pending.current?.token,
      onError(event) {
        if (!rejection[event.code]) {
          unknown()
          void channel.current?.refresh()
          return
        }
        mark(null)
        setSendError(rejection[event.code])
        if (event.code !== 'INVALID_INPUT') setClosed(true)
      },
      onAccessError(error) {
        setMessages([])
        setDenied(true)
        setDraft('')
        mark(null)
        accessHandler.current?.(error)
      },
    })
    channel.current = chat
    return () => { clearTimeout(deadline.current); chat.close(); channel.current = null }
  }, [roomId, live, role, viewerId, since, storageKey])

  const content = draft.trim()
  const tooLong = Array.from(content).length > 1000
  const writable = live && !closed && !denied
  const canSend = writable && connected && content && !tooLong &&
      (!attempt || (attempt.uncertain && history === 'ready'))

  function submit(event) {
    event.preventDefault()
    if (!canSend || (pending.current && !pending.current.uncertain)) return
    // 결과를 확인하지 못한 전송을 다시 보낼 때는 같은 식별자를 써서, 서버가 이미 저장했다면 중복 저장하지 않게 한다.
    const token = attempt?.uncertain ? attempt.token : randomUuid()
    mark({ token, content, uncertain: false })
    setSendError('')
    try {
      channel.current.send(content, token)
      deadline.current = setTimeout(() => { unknown(); void channel.current?.refresh() }, 10000)
    } catch {
      unknown()
      void channel.current?.refresh()
    }
  }

  return <section className={`chat-panel${enhanced ? ' rl-chat-panel' : ''}`} aria-label="강연 채팅">
    <div className="chat-panel-heading">
      <h2>{enhanced ? '실시간 채팅' : '채팅'}</h2>
      {live && !connected && !denied && <span>연결 확인 중</span>}
    </div>
    {denied ? <p role="alert">채팅을 이용할 권한이 없어요.</p> : <>
      <MessageList messages={messages} viewer={viewer} history={history}
                   visible={visible} rememberPositionRef={rememberPositionRef}
                   enhanced={enhanced} />
      {history === 'failed' && <div className="chat-feedback" role="alert">채팅 내역을 불러오지 못했어요.
        <button type="button" onClick={() => channel.current?.refresh()}>다시 불러오기</button></div>}
      {writable && <form
          className={`chat-composer${enhanced && attempt?.uncertain ? ' rl-chat-retry' : ''}`}
          onSubmit={submit}
      >
        <label htmlFor={composerId}>메시지</label>
        <textarea id={composerId} rows={2} value={draft} readOnly={Boolean(attempt)}
                  placeholder={enhanced ? '메시지를 입력하세요...' : undefined}
                  onKeyDown={event => {
                    if (event.key !== 'Enter' || event.shiftKey || event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229) return
                    event.preventDefault()
                    if (canSend) event.currentTarget.form.requestSubmit()
                  }}
                  onChange={event => { setDraft(event.target.value); setSendError('') }} />
        {attempt?.uncertain && <p role="alert">{uncertainText}</p>}
        {tooLong && <p role="alert">{rejection.INVALID_INPUT}</p>}
        {sendError && <p role="alert">{sendError}</p>}
        <div className="chat-actions">
          {attempt?.uncertain && <button type="button" className="chat-discard" onClick={() => {
            mark(null); setDraft(''); setSendError('')
          }}>문구 지우기</button>}
          <button
              type="submit"
              disabled={!canSend}
              aria-label={enhanced && !attempt ? '메시지 보내기' : undefined}
          >
            {attempt
                ? attempt.uncertain ? '다시 보내기' : '전송 중'
                : enhanced ? <SendIcon /> : '보내기'}
          </button>
        </div>
      </form>}
      {closed && live && <p role="status">{sendError}</p>}
    </>}
  </section>
}

function MessageList({ messages, viewer, history, visible, rememberPositionRef, enhanced = false }) {
  const list = useRef(null)
  const pinned = useRef(true)
  const readingTop = useRef(0)
  const [seen, setSeen] = useState(0)
  const latest = messages.at(-1)?.messageId ?? 0
  const latestRef = useRef(latest)

  function rememberPosition() {
    const node = list.current
    // 패널을 숨긴 뒤 도착한 scroll 이벤트는 마지막으로 보던 위치를 바꾸지 않는다.
    if (!node?.clientHeight) return
    readingTop.current = node.scrollTop
    pinned.current = node.scrollHeight - node.scrollTop - node.clientHeight <= 16
    if (pinned.current) setSeen(latestRef.current)
  }
  useImperativeHandle(rememberPositionRef, () => rememberPosition)

  function goToLatest() {
    const node = list.current
    if (!node || !node.clientHeight) return
    node.scrollTop = node.scrollHeight
    pinned.current = true
    setSeen(latestRef.current)
  }
  useLayoutEffect(() => {
    latestRef.current = latest
    if (visible && pinned.current) goToLatest()
  }, [latest, visible])
  useLayoutEffect(() => {
    if (visible && !pinned.current) list.current.scrollTop = readingTop.current
  }, [visible])
  useLayoutEffect(() => {
    const observer = new ResizeObserver(() => { if (pinned.current) goToLatest() })
    observer.observe(list.current)
    return () => observer.disconnect()
  }, [])

  const unseen = seen && !pinned.current ? messages.filter(message => message.messageId > seen).length : 0
  return <div className="chat-stream">
    <div role="log" aria-label="채팅 메시지" aria-live="polite" aria-relevant="additions" tabIndex={0} ref={list} className="chat-log"
         onScroll={rememberPosition}>
      {messages.length > 0 ? <ol>{messages.map(message => {
        const mine = message.sender.type === viewer.type && message.sender.id === viewer.id
        return <li key={message.messageId} className={mine ? 'chat-own' : undefined}>

          {enhanced && <ChatAvatar sender={message.sender} />}

          <div className="chat-meta">
            <strong>{message.sender.name}</strong>
            {message.sender.type === 'HOST' && <span className="chat-host">강연자</span>}
            <time dateTime={message.receivedAt}>{time.format(new Date(message.receivedAt))}</time>
          </div>
          <p>{message.content}</p>
        </li>
      })}</ol> : <p className="chat-empty">{history === 'ready' ? '아직 메시지가 없어요.' :
          history === 'loading' ? '채팅 내역을 불러오는 중이에요.' : ''}</p>}
    </div>
    {unseen > 0 && <button type="button" className="chat-jump" onClick={goToLatest}>새 메시지 {unseen}개 ↓</button>}
  </div>
}
