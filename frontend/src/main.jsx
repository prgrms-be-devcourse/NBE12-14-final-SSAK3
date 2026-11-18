import React from 'react'
import { createRoot } from 'react-dom/client'
import { SttDemo } from './stt/SttDemo.jsx'

// SCRUM-98 화면에 SttPanel을 연결하기 전까지의 실제 API 확인용 화면.
createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <SttDemo />
  </React.StrictMode>,
)
