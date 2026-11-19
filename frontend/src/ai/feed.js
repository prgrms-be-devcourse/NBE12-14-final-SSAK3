// A feed owns one room. Dispose invalidates every outstanding response, including source requests.
export const initialFeed = () => ({ room: null, analyses: [], visibleAnalyses: [], current: null,
  candidate: null, openIds: [], sources: {}, limit: 10, busy: false, loaded: false,
  error: false, fatal: null, checkedAt: null })

export function sortedAnalyses(analyses) {
  return [...analyses].sort((a, b) => Date.parse(b.windowStartAt) - Date.parse(a.windowStartAt)
    || b.analysisId - a.analysisId)
}

export const latestSummary = analyses => analyses.find(a => a.status === 'COMPLETED' && a.topicGroups.length > 0)

function newerSummary(incoming, previous) {
  if (!incoming) return previous
  if (!previous) return incoming
  return sortedAnalyses([incoming, previous])[0]
}

export function createAudienceFeed({ roomId, request, onChange, onUnauthenticated,
  hidden = () => document.hidden }) {
  let state = initialFeed(), active = true, epoch = 0, timer, running = false, stopped = false
  let wantedLimit = 10
  const base = `/rooms/${encodeURIComponent(roomId)}`
  const emit = patch => { state = { ...state, ...patch }; onChange(state) }
  const valid = token => active && token === epoch
  function fatal(status) {
    stopped = true; epoch++; clearTimeout(timer)
    emit({ ...initialFeed(), fatal: status })
    if (status === 401) onUnauthenticated()
  }
  function applyView() {
    emit({ current: state.candidate, visibleAnalyses: state.analyses, openIds: [] })
  }
  function accept(data, limit) {
    if (String(data?.roomId) !== String(roomId) || !Array.isArray(data.analyses)) throw new Error('INVALID_ANALYSES')
    const analyses = sortedAnalyses(data.analyses)
    const candidate = newerSummary(latestSummary(analyses), state.candidate)
    const patch = { analyses, candidate, limit, loaded: true, error: false, checkedAt: new Date().toISOString() }
    if (!state.openIds.length) Object.assign(patch, { current: candidate, visibleAnalyses: analyses })
    else if (limit > state.limit) {
      // An explicit range expansion can reveal older results without dropping an open source card.
      const pinned = state.visibleAnalyses.filter(a => a.topicGroups.some(t => state.openIds.includes(t.topicGroupId)))
      const byId = new Map([...analyses, ...pinned].map(a => [a.analysisId, a]))
      patch.visibleAnalyses = sortedAnalyses([...byId.values()])
    }
    emit(patch)
  }
  async function load() {
    if (!active || running || stopped || hidden()) return
    running = true; clearTimeout(timer)
    const token = epoch
    emit({ busy: true })
    try {
      if (!state.room) {
        await request('/auth/me')
        if (!valid(token)) return
        const room = await request(base)
        if (!valid(token)) return
        if (String(room?.roomId) !== String(roomId)) throw new Error('INVALID_ROOM')
        emit({ room })
      }
      const requestedLimit = wantedLimit
      let data = await request(`${base}/ai/analyses?limit=${requestedLimit}`)
      if (!valid(token)) return
      // On initial entry, look farther back once before claiming no recent summary exists.
      if (!state.loaded && requestedLimit === 10 && Array.isArray(data?.analyses)
          && !latestSummary(data.analyses)) {
        wantedLimit = 50
        data = await request(`${base}/ai/analyses?limit=50`)
        if (!valid(token)) return
        accept(data, 50)
      } else accept(data, requestedLimit)
    } catch (error) {
      if (!valid(token)) return
      if ([401, 403, 404].includes(error.status)) fatal(error.status)
      else { stopped = true; emit({ error: true }) }
    } finally {
      running = false
      if (valid(token)) {
        emit({ busy: false })
        if (!stopped && !hidden()) {
          // A 'more' click while a poll is in flight is queued, not lost or sent concurrently.
          timer = setTimeout(load, wantedLimit > state.limit ? 0 : 5000)
        }
      }
    }
  }
  async function loadSources(topicGroupId) {
    if (!active || state.fatal || state.sources[topicGroupId]?.status === 'loading') return
    const token = epoch
    emit({ sources: { ...state.sources, [topicGroupId]: { status: 'loading' } } })
    try {
      const data = await request(`${base}/ai/topic-groups/${topicGroupId}/messages`)
      if (!valid(token)) return
      if (data?.topicGroupId !== topicGroupId || !Array.isArray(data.messages)) throw new Error('INVALID_SOURCES')
      emit({ sources: { ...state.sources, [topicGroupId]: { status: 'ready', messages: data.messages } } })
    } catch (error) {
      if (!valid(token)) return
      if ([401, 403].includes(error.status)) fatal(error.status)
      else emit({ sources: { ...state.sources, [topicGroupId]: {
        status: error.status === 404 ? 'missing' : 'error',
      } } })
    }
  }
  return {
    start: load,
    reconnect() { if (state.fatal) return; stopped = false; load() },
    more() { if (state.fatal || stopped) return; wantedLimit = 50; load() },
    visibilityChanged() { clearTimeout(timer); if (!hidden()) load() },
    toggleSource(id) {
      if (!active || state.fatal) return
      if (state.openIds.includes(id)) {
        const openIds = state.openIds.filter(value => value !== id)
        emit({ openIds })
        if (!openIds.length) applyView()
      } else {
        emit({ openIds: [...state.openIds, id] })
        if (!state.sources[id]) loadSources(id)
      }
    },
    retrySource: loadSources,
    closeSources(ids) {
      if (!active || state.fatal) return
      const openIds = state.openIds.filter(id => !ids.includes(id))
      emit({ openIds })
      if (!openIds.length) applyView()
    },
    showLatest: applyView,
    dispose() { active = false; epoch++; clearTimeout(timer); state = initialFeed() },
  }
}
