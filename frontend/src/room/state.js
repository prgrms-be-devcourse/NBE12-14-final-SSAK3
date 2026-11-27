export const draftKey = 'react:room-draft:v1'

export const emptyDraft = {
  title: '',
  description: '',
  uncertain: false,
}

export function returnPath(value) {
  return /^(?:\/host\/rooms(?:\/(?:new|history|[1-9]\d*(?:\/report)?))?|\/dev\/rooms\/[1-9]\d*\/ai)$/.test(value || '')
      ? value
      : null
}

export function loginFor(path) {
  location.replace(
      `/login?returnTo=${encodeURIComponent(
          returnPath(path) || '/host/rooms',
      )}`,
  )
}

export function clearDraft(userId) {
  try {
    const value = JSON.parse(
        sessionStorage.getItem(draftKey) || 'null',
    )

    if (
        value &&
        String(value.userId) === String(userId)
    ) {
      sessionStorage.removeItem(draftKey)
    }
  } catch {
    /* A blocked store cannot be read or restored. */
  }
}

export function readDraft(userId) {
  try {
    const value = JSON.parse(
        sessionStorage.getItem(draftKey) || 'null',
    )

    if (!value) return { ...emptyDraft }

    if (String(value.userId) !== String(userId)) {
      sessionStorage.removeItem(draftKey)

      return { ...emptyDraft }
    }

    return {
      title: typeof value.title === 'string'
          ? value.title
          : '',

      description: typeof value.description === 'string'
          ? value.description
          : '',

      uncertain: value.uncertain === true,
    }
  } catch {
    return { ...emptyDraft }
  }
}

export function writeDraft(userId, draft) {
  try {
    sessionStorage.setItem(
        draftKey,
        JSON.stringify({
          userId,
          ...draft,
        }),
    )

    return true
  } catch {
    return false
  }
}

export const terminal = error =>
    [401, 403, 404].includes(error.status)

export const errorText = error =>
    error?.status === 403
        ? '이 강연방을 볼 권한이 없습니다.'
        : error?.status === 404
            ? '강연방을 찾을 수 없습니다.'
            : '서버에 연결하지 못했습니다. 다시 확인해 주세요.'