export function speechConstructor() {
  return globalThis.SpeechRecognition || globalThis.webkitSpeechRecognition
}

// Indexes are only stable during one recognition run, so a new tracker is used per run.
export class TranscriptTracker {
  constructor() { this.firstSeen = new Map(); this.finalized = new Set() }
  accept(event, time) {
    const finals = []
    const interim = []
    for (const index of this.firstSeen.keys()) {
      if (index >= event.results.length) this.firstSeen.delete(index)
    }
    for (let index = 0; index < event.results.length; index++) {
      const result = event.results[index]
      if (this.finalized.has(index)) continue
      if (!this.firstSeen.has(index)) this.firstSeen.set(index, time)
      const transcript = result[0]?.transcript || ''
      if (result.isFinal) {
        this.finalized.add(index)
        if (transcript.trim()) finals.push({
          transcript,
          segmentStartAt: this.firstSeen.get(index),
          segmentEndAt: time,
        })
      } else interim.push(transcript)
    }
    return { finals, interim: interim.join(' ') }
  }
}

export function createSpeech({ onResult, onEnd, now = () => new Date().toISOString(), Recognition = speechConstructor() }) {
  let current = null
  return {
    start() {
      if (!Recognition) return Promise.reject(new Error('UNSUPPORTED'))
      if (current) return Promise.reject(new Error('ALREADY_LISTENING'))
      return new Promise((resolve, reject) => {
        const recognition = new Recognition()
        const tracker = new TranscriptTracker()
        const run = { recognition, started: false, error: null, stopResolve: null }
        current = run
        recognition.lang = 'ko-KR'
        recognition.continuous = true
        recognition.interimResults = true
        const cleanup = () => {
          clearTimeout(run.startTimer)
          clearTimeout(run.stopTimer)
          recognition.onresult = recognition.onstart = recognition.onerror = recognition.onend = null
          if (current === run) current = null
        }
        const finish = () => {
          if (run.finished) return
          run.finished = true
          cleanup()
          if (!run.started) reject(new Error(run.error || 'START_FAILED'))
          if (run.stopResolve) run.stopResolve()
          else if (run.started) onEnd(run.error)
        }
        run.finish = finish
        recognition.onstart = () => {
          run.started = true
          clearTimeout(run.startTimer)
          resolve()
        }
        recognition.onresult = event => onResult(tracker.accept(event, now()))
        recognition.onerror = event => {
          run.error = event.error
          // Some engines do not send end after a fatal error. Bound the wait.
          clearTimeout(run.stopTimer)
          run.stopTimer = setTimeout(() => { recognition.abort(); finish() }, 2000)
        }
        recognition.onend = finish
        run.startTimer = setTimeout(() => {
          run.error = 'START_TIMEOUT'
          recognition.abort()
          finish()
        }, 10000)
        try { recognition.start() } catch (error) { cleanup(); reject(error) }
      })
    },
    stop() {
      if (!current) return Promise.resolve()
      const run = current
      if (run.stopPromise) return run.stopPromise
      run.stopPromise = new Promise(resolve => {
        run.stopResolve = resolve
        run.stopTimer = setTimeout(() => { run.recognition.abort(); run.finish() }, 3000)
        try { run.recognition.stop() } catch { run.finish() }
      })
      return run.stopPromise
    },
    abort() {
      if (!current) return
      const run = current
      run.stopResolve ||= () => {}
      run.recognition.abort()
      run.finish()
    },
  }
}
