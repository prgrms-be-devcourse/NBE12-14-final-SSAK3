// One atomic snapshot per account and room; credentials are never persisted.
export function createStore(key, indexedDB = globalThis.indexedDB) {
  let database
  async function db() {
    if (!database) database = new Promise((resolve, reject) => {
      const request = indexedDB.open('react-stt', 1)
      request.onupgradeneeded = () => request.result.createObjectStore('recordings')
      request.onsuccess = () => resolve(request.result)
      request.onerror = () => reject(request.error)
      request.onblocked = () => reject(new Error('STORAGE_BLOCKED'))
    })
    return database
  }
  async function transaction(mode, run) {
    const database = await db()
    return new Promise((resolve, reject) => {
      const tx = database.transaction('recordings', mode)
      const request = run(tx.objectStore('recordings'))
      tx.oncomplete = () => resolve(request.result)
      tx.onerror = () => reject(tx.error)
      tx.onabort = () => reject(tx.error || new Error('STORAGE_ABORTED'))
    })
  }
  return {
    load: () => transaction('readonly', store => store.get(key)),
    save: value => transaction('readwrite', store => store.put(value, key)),
  }
}

// The lock is held until the controller is disposed, including while paused.
export function acquireTab(key, locks = globalThis.navigator?.locks) {
  if (!locks) return Promise.reject(new Error('TAB_LOCK_UNSUPPORTED'))
  return new Promise((resolve, reject) => {
    locks.request(`react-stt:${key}`, { ifAvailable: true }, lock => {
      if (!lock) { resolve(null); return }
      return new Promise(release => resolve(release))
    }).catch(reject)
  })
}
