import React from 'react'
import { createRoot } from 'react-dom/client'
import { SttDemo } from './stt/SttDemo.jsx'
import { ReportPreview } from './report/ReportPreview.jsx'
import { RoomApp } from './room/RoomApp.jsx'
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { AuthPage } from './auth/AuthPage.jsx'
import { EntryPage } from './auth/EntryPage.jsx'

// 정식 진입 경로와 STT 확인용 경로를 분리한다.
createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <BrowserRouter><Routes>
      <Route path="/" element={<EntryPage />} />
      <Route path="/login" element={<AuthPage />} />
      <Route path="/signup" element={<AuthPage />} />
      <Route path="/host/rooms/:roomId" element={<RoomApp />} />
      <Route path="/preview/reports" element={<ReportPreview />} />
      <Route path="/dev/stt" element={<SttDemo />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes></BrowserRouter>
  </React.StrictMode>,
)
