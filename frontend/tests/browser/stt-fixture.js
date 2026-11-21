export async function setup(context) {
  const state = {
    sends: [],
    keys: [],
    stopped: false,
    offline: false,
    account: 1,
    gaps: [],
    csrfToken: 'test-csrf-token',
    csrfRequests: 0,
    csrfWrites: [],
  }

  await context.addInitScript(() => {
    class SpeechRecognition {
      constructor() { window.testSpeech = this; this.results = [] }
      start() { setTimeout(() => this.onstart?.(), 5) }
      stop() { setTimeout(() => this.onend?.(), 5) }
      abort() { setTimeout(() => this.onend?.(), 0) }
      emit(text, final = true) {
        const result = Object.assign([{ transcript: text }], { isFinal: final })
        if (this.results.length && !this.results.at(-1).isFinal) this.results[this.results.length - 1] = result
        else this.results.push(result)
        this.onresult?.({ resultIndex: this.results.length - 1, results: this.results })
      }
    }
    window.SpeechRecognition = SpeechRecognition
  })

  await context.route('**/api/v1/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()
    const body = request.postDataJSON()
    const ok = data => route.fulfill({ json: { data } })

    if (path.endsWith('/auth/csrf')) {
      state.csrfRequests++
      return ok({
        headerName: 'X-CSRF-TOKEN',
        token: state.csrfToken,
      })
    }

    if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(method)) {
      const token = request.headers()['x-csrf-token']

      if (token !== state.csrfToken) {
        return route.fulfill({
          status: 403,
          json: { code: 'FORBIDDEN' },
        })
      }

      state.csrfWrites.push({ path, method, token })
    }

    if (path.endsWith('/auth/me')) return ok({ userId: state.account, name: '문강사', email: 'host@example.test' })
    if (path.endsWith('/auth/login')) return ok({ authenticated: true })
    if (path.endsWith('/stt/segments')) return ok({ roomId: 10, segments: [], gaps: state.gaps })
    if (path.endsWith('/sessions')) {
      state.keys.push(body.startKey)
      return ok({ sttSessionId: 7, status: state.stopped ? 'STOPPED' : 'RECORDING', canRecord: !state.stopped })
    }
    if (path.endsWith('/segments')) {
      state.sends.push(body)
      if (state.offline) return route.abort()
      return ok({ segmentId: state.sends.length })
    }
    if (path.endsWith('/gaps')) {
      state.gaps = [{ gapId: 9, sessionId: 7, startedAt: body.startedAt, endedAt: null }]
      return ok({ gapId: 9 })
    }
    if (path.endsWith('/close')) { state.gaps = []; return ok({ gapId: 9 }) }
    if (path.endsWith('/stop')) { state.stopped = true; return ok({ sttSessionId: 7, status: 'STOPPED', canRecord: false }) }
    return route.fulfill({ status: 404, json: { code: 'NOT_FOUND' } })
  })

  return state
}
