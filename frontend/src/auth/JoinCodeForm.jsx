import React, { useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Icon } from '../ui/Icon.jsx'
import { StudioLayout } from './StudioLayout.jsx'

export function JoinCodeForm() {
  const [code, setCode] = useState('')
  const [invalid, setInvalid] = useState(false)
  const input = useRef(null)
  const navigate = useNavigate()

  function join(event) {
    event.preventDefault()
    const normalized = code.trim().toUpperCase()
    if (!/^[A-Z0-9]+$/.test(normalized)) {
      setInvalid(true)
      input.current.focus()
      return
    }
    navigate('/join/' + encodeURIComponent(normalized))
  }

  return <>
    <div className="auth-heading"><h1>강연에 참여하기</h1><p>강연자가 공유한 참여 코드를 입력해 주세요.</p></div>
    <form className="studio-form" onSubmit={join} noValidate>
      <div className="auth-field"><label htmlFor="join-code">참여 코드</label>
        <div className="auth-input"><Icon name="qr" size={18} />
          <input id="join-code" ref={input} required placeholder="참여 코드 입력" value={code} spellCheck={false}
            autoComplete="off" autoCapitalize="characters" aria-invalid={invalid} aria-describedby={invalid ? 'join-code-error' : undefined}
            onChange={event => { setCode(event.target.value); setInvalid(false) }} />
        </div>
        {invalid && <p id="join-code-error" className="auth-field-error" role="alert">영문과 숫자로 된 참여 코드를 입력해 주세요.</p>}
      </div>
      <button className="auth-submit">강연방 입장<Icon name="arrow-right" size={18} /></button>
    </form>
    <p className="studio-join-hint"><Icon name="qr" size={18} />초대 QR을 스캔해도 참여할 수 있어요.</p>
  </>
}

export function JoinCodePage() {
  return <StudioLayout audience><JoinCodeForm /></StudioLayout>
}
