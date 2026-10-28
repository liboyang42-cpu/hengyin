'use strict'
const { exactSessionId } = require('./roam-recovery.js')
const { compareCursors } = require('./roam-tile-cursor.js')
const { currentPlayerRoamMemory } = require('./roam-player-memory.js')

const count = value => value == null ? null : typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : (() => { throw new Error('统计格式无效') })()
function fact(raw) {
  if (!raw || typeof raw.sessionId !== 'string' || exactSessionId(raw.sessionId) !== raw.sessionId) throw new Error('记录编号无效')
  const finished = count(raw.finishedAt), started = count(raw.startedAt), distance = count(raw.distanceM)
  return { sessionId: raw.sessionId, ts: finished || started || 0, finishedAt: finished,
    distance: distance == null ? null : distance / 1000, shops: count(raw.shops),
    durSec: finished != null && started != null && finished >= started ? Math.floor((finished - started) / 1000) : null,
    photos: [], track: [], pois: [], serverFact: true }
}
function merge(local, remote) {
  const ids = new Set(remote.map(row => row.sessionId))
  const combined = remote.map(row => {
    const saved = local.find(item => exactSessionId(item.sessionId) === row.sessionId
      && Number.isFinite(Number(item.ts)) && Number(item.ts) > 0
      && ['track', 'pois'].every(key => item[key] == null || Array.isArray(item[key]) && item[key].every(point => {
        if (!point || typeof point !== 'object' || Array.isArray(point)) return false
        if (point.lat == null && point.lng == null) return true
        return [['lat', 90], ['lng', 180]].every(([axis, max]) =>
          (typeof point[axis] === 'number' || typeof point[axis] === 'string' && point[axis].trim() !== '')
          && Number.isFinite(Number(point[axis])) && Math.abs(Number(point[axis])) <= max)
      })))
    return { ...saved, ...row, ts: saved && saved.ts || row.ts, localSupplement: !!saved, photos: saved && saved.photos || [], track: saved && saved.track || [], pois: saved && saved.pois || [],
      historyKey: 'server:' + row.sessionId }
  })
  // Legacy records remain local records; never upload them or add them to server passport totals.
  return combined.concat(local.filter(row => !ids.has(exactSessionId(row.sessionId))).map(row => ({ ...row, historyKey: 'local:' + row.ts })))
}
function createReader(app, platform) {
  const owner = exactSessionId(app.getUserID && app.getUserID())
  const token = app.getAuthorization ? app.getAuthorization() : (app.globalData || {}).authorization
  let disposed = false
  const current = () => !disposed && owner === exactSessionId(app.getUserID && app.getUserID())
    && token === (app.getAuthorization ? app.getAuthorization() : (app.globalData || {}).authorization)
  function request(path, data) {
    return new Promise((resolve, reject) => {
      if (!owner || !current()) return reject(new Error('请登录后读取本人的漫游'))
      let settled = false
      const finish = (value, error) => {
        if (settled) return
        settled = true
        if (!current()) return reject(new Error('账号或页面已变化'))
        if (error) reject(error); else resolve(value)
      }
      try {
        app.sendRequest({ url: path, method: 'GET', data,
          hideLoading: true, autoErrorToast: false,
          success: res => res && String(res.code) === '200' ? finish(res.data) : finish(null, Object.assign(new Error('漫游记录暂时无法读取'), { notFound: res && String(res.code) === '404' })),
          fail: () => finish(null, new Error('漫游记录暂时无法读取')),
          successStatusAbnormal: () => finish(null, new Error('漫游记录暂时无法读取')) })
      } catch (error) { finish(null, error) }
    })
  }
  return {
    current, dispose() { disposed = true },
    local() {
      if (!current()) throw new Error('账号已变化')
      const state = currentPlayerRoamMemory(app, platform).readSessionState()
      if (!state.ok || state.sessions.some(row => !row || typeof row !== 'object' || Array.isArray(row))) throw new Error('本机历史暂时无法读取')
      return state.sessions
    },
    async page(before) {
      if (before && exactSessionId(before) !== before) throw new Error('游标无效')
      const raw = await request('/api/roam/passport/history', before ? { before, limit: 30 } : { limit: 30 })
      if (!raw || !Array.isArray(raw.items) || raw.items.length > 30 || typeof raw.hasMore !== 'boolean') throw new Error('历史分页无效')
      const rows = raw.items.map(fact)
      let last = before || null
      for (const row of rows) {
        if (last && compareCursors(row.sessionId, last) >= 0) throw new Error('历史分页未推进')
        last = row.sessionId
      }
      if (raw.hasMore && (!rows.length || raw.nextBefore !== last)) throw new Error('历史分页未推进')
      return { rows, nextBefore: raw.hasMore ? last : null }
    },
    async summary() {
      const raw = await request('/api/roam/passport/summary', {})
      if (!raw || raw.trips == null) throw new Error('护照统计无效')
      return { trips: count(raw.trips), distanceM: count(raw.distanceM), days: count(raw.days), shops: count(raw.shops) }
    },
    async record(id) {
      if (exactSessionId(id) !== id) throw new Error('记录编号无效')
      const row = fact(await request('/api/roam/passport/record', { sessionId: id }))
      if (row.sessionId !== id) throw new Error('记录身份不一致')
      let local = []
      try { local = this.local() } catch (_) { /* Remote facts do not depend on local storage. */ }
      return merge(local, [row])[0]
    },
  }
}
module.exports = { createReader, merge }
