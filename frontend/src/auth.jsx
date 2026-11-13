import React, { createContext, useContext, useEffect, useState } from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { authStorage } from './api'

const AuthContext = createContext(null)
export function AuthProvider({ children }) {
  const [user, setUser] = useState(() => authStorage.token() ? { email: authStorage.email() } : null)
  const [expired, setExpired] = useState(false)
  useEffect(() => {
    const handler = () => { setUser(null); setExpired(true) }
    window.addEventListener('csmatch:expired', handler)
    return () => window.removeEventListener('csmatch:expired', handler)
  }, [])
  function login(token, email) { authStorage.save(token, email); setUser({ email }); setExpired(false) }
  function logout() { authStorage.clear(); setUser(null); setExpired(false) }
  return <AuthContext.Provider value={{ user, expired, login, logout }}>{children}</AuthContext.Provider>
}
export const useAuth = () => useContext(AuthContext)
export function RequireAuth({ children }) {
  const { user } = useAuth()
  const location = useLocation()
  return user ? children : <Navigate to="/login" state={{ from: location.pathname + location.search }} replace />
}
