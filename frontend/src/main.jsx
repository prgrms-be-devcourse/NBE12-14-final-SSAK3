import React from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AuthProvider, RequireAuth } from './auth'
import { Shell } from './components/Shell'
import Lobby from './pages/Lobby'
import AuthPage from './pages/AuthPage'
import PracticeHome from './pages/PracticeHome'
import PracticeSession from './pages/PracticeSession'
import './style.css'
import './practice.css'

createRoot(document.getElementById('root')).render(
  <React.StrictMode><BrowserRouter><AuthProvider><Shell><Routes>
    <Route path="/" element={<Lobby />} />
    <Route path="/login" element={<AuthPage key="login" />} />
    <Route path="/signup" element={<AuthPage key="signup" signup />} />
    <Route path="/practice" element={<RequireAuth><PracticeHome /></RequireAuth>} />
    <Route path="/practice/:sessionId" element={<RequireAuth><PracticeSession /></RequireAuth>} />
    <Route path="*" element={<Navigate to="/" replace />} />
  </Routes></Shell></AuthProvider></BrowserRouter></React.StrictMode>,
)
