import React, { useRef, useState, useSyncExternalStore } from 'react'
import { AudienceRoomPanel } from '../ai/AudienceAnalysisPage.jsx'
import { ChatPanel } from '../chat/ChatPanel.jsx'
import { SttPanel } from '../stt/SttPanel.jsx'
import { SavedSpeech } from './SavedSpeech.jsx'
import { PollCreatePanel } from '../poll/PollCreatePanel.jsx'
import { UnderstandingSummary } from '../poll/UnderstandingSummary.jsx'
import { PollResults } from '../poll/PollResults.jsx'

const compactLayout = '(max-width: 960px)'
const tabs = [{ id: 'ai', label: 'AI 요약' }, { id: 'chat', label: '채팅' }]

function watchLayout(update) {
  const media = window.matchMedia(compactLayout)
  media.addEventListener('change', update)
  return () => media.removeEventListener('change', update)
}

function isCompact() {
  return window.matchMedia(compactLayout).matches
}

export function RoomWorkspace({ room, userId, onRecorder, ending, onAccessError }) {
  const compact = useSyncExternalStore(watchLayout, isCompact)
  const [activeTab, setActiveTab] = useState('ai')
  const rememberChatPosition = useRef(null)
  const live = room.status === 'LIVE'

  function selectTab(target) {
    if (activeTab === 'chat' && target !== 'chat') rememberChatPosition.current?.()
    setActiveTab(target)
  }

  function changeTabByKey(event) {
    let target
    if (event.key === 'Home') target = 'ai'
    else if (event.key === 'End') target = 'chat'
    else if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') target = activeTab === 'ai' ? 'chat' : 'ai'
    else return
    event.preventDefault()
    selectTab(target)
    document.getElementById(`room-tab-${target}`).focus()
  }

  function panelProps(id) {
    return {
      id: `room-panel-${id}`,
      role: compact ? 'tabpanel' : undefined,
      tabIndex: compact ? 0 : undefined,
      'aria-labelledby': compact ? `room-tab-${id}` : undefined,
      hidden: compact && activeTab !== id,
    }
  }

  return <div className="room-workspace">
    {live && <div className="room-recording">
      <SttPanel userId={userId} roomId={room.roomId}
                onRecorder={onRecorder} ending={ending} />
    </div>}

    {live && <PollCreatePanel roomId={room.roomId} ending={ending} />}

    <UnderstandingSummary roomId={room.roomId} />

    <PollResults roomId={room.roomId} />

    {compact && <div className="room-panel-tabs" role="tablist" aria-label="강연 패널">
      {tabs.map(({ id, label }) => <button key={id} id={`room-tab-${id}`} role="tab"
                                           aria-controls={`room-panel-${id}`} aria-selected={activeTab === id}
                                           tabIndex={activeTab === id ? 0 : -1}
                                           onClick={() => selectTab(id)} onKeyDown={changeTabByKey}>
        {label}
      </button>)}
    </div>}

    <div className="room-insights" {...panelProps('ai')}>
      <AudienceRoomPanel roomId={room.roomId} onAccessError={onAccessError} />
    </div>

    <div className="room-chat" {...panelProps('chat')}>
      <ChatPanel roomId={room.roomId} live={live}
                 visible={!compact || activeTab === 'chat'} rememberPositionRef={rememberChatPosition}
                 viewer={{ type: 'HOST', id: userId }}
                 since={room.startedAt} onAccessError={onAccessError} />
    </div>

    {!live && <SavedSpeech roomId={room.roomId} onAccessError={onAccessError} />}
  </div>
}