import React, { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { request } from '../stt/api.js'
import { JoinCodeForm } from './JoinCodeForm.jsx'
import { StudioLayout } from './StudioLayout.jsx'

export function EntryPage() {
  const navigate = useNavigate()

  const [error, setError] = useState(false)
  const [revision, setRevision] = useState(0)

  useEffect(() => {
    let active = true

    request('/auth/me')
        .then(() => {
          if (active) {
            navigate('/host/rooms', {
              replace: true,
            })
          }
        })
        .catch(e => {
          if (!active) return

          if (e.status === 401) {
            navigate('/login', {
              replace: true,
            })
          } else {
            setError(true)
          }
        })

    return () => {
      active = false
    }
  }, [navigate, revision])

  return <StudioLayout audience={error}>

    {error
        ? <>
          <JoinCodeForm />

          <div className="auth-session-note">

            <p role="alert">
              로그인 상태를 확인하지 못했습니다.
              서버 연결을 확인해 주세요.
            </p>

            <button
                className="auth-text-button"
                onClick={() => {
                  setError(false)
                  setRevision(value => value + 1)
                }}
            >
              다시 확인
            </button>

          </div>
        </>
        : <div className="auth-heading">

          <h1>스튜디오 입장</h1>

          <p role="status">
            로그인 상태를 확인하고 있습니다.
          </p>

        </div>}

  </StudioLayout>
}