import React, {
    useState,
    useSyncExternalStore,
} from 'react'

import { speechConstructor } from '../../stt/speech.js'

const emptySubscribe = () => () => {}
const emptySnapshot = () => null

export function LiveRecordingControl({
                                         recorder,
                                         ending = false,
                                         offline = false,
                                     }) {
    const state = useSyncExternalStore(
        recorder?.subscribe || emptySubscribe,
        recorder?.getSnapshot || emptySnapshot,
    )

    const [error, setError] = useState('')

    const supported =
        !!speechConstructor() &&
        !!navigator.locks &&
        !!globalThis.isSecureContext

    const recording = state?.phase === 'listening'
    const starting = state?.phase === 'starting'
    const stopping = state?.phase === 'stopping'

    const disabled =
        !recorder ||
        !state ||
        ending ||
        state.busy ||
        (recording
            ? !state.hasSession || !state.visible
            : offline ||
            !supported ||
            !state.canStart ||
            state.stopPending)

    const label =
        !state || state.phase === 'loading'
            ? '녹음 확인 중'
            : starting
                ? '녹음 준비 중'
                : stopping || state.stopPending
                    ? '기록 저장 중'
                    : recording
                        ? '녹음 중지'
                        : state.hasSession && state.phase !== 'ended'
                            ? '녹음 재개'
                            : '녹음 시작'

    async function toggleRecording() {
        if (disabled) return

        setError('')

        try {
            if (recording) {
                await recorder.stop()
            } else {
                await recorder.start()
            }
        } catch {
            setError(
                '발언 기록에서 녹음 및 저장 상태를 확인해 주세요.',
            )
        }
    }

    return <div className="rl-record-control rl-record-control-round">

        <button
            type="button"
            className={`rl-record-toggle${recording ? ' is-recording' : ''}`}
            disabled={disabled}
            title={!supported
                ? '음성 인식을 지원하는 Chrome 환경에서 사용해 주세요.'
                : label}
            onClick={toggleRecording}
        >
            <span className="rl-record-ring" aria-hidden="true">

                <span className={
                    recording
                        ? 'rl-record-symbol rl-record-symbol-stop'
                        : 'rl-record-symbol'
                } />

            </span>

            <span className="rl-record-label">
                {label}
            </span>
        </button>

        {error && <small role="alert">
            {error}
        </small>}

    </div>
}