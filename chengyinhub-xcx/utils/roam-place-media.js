'use strict'

const PM = require('./roam-place-map.js')
const inFlight = new Map()
function manifestPath(key) {
  return typeof key === 'string' && /^[a-zA-Z0-9_-]+(?:\/[a-zA-Z0-9_-]+)*\.(?:jpg|png|webp)$/.test(key)
    ? '/profile/' + key.replace(/\.[^.]+$/, '.gallery.json') : ''
}
function applyManifest(row, data) {
  const key = row.cover || row.coverImg || row.cover_img
  if (!data || String(data.poiId) !== String(row.id == null ? row.poiId : row.id) || data.coverKey !== key || typeof data.credit !== 'string' || !data.credit.trim()) {
    return Object.assign({}, row, { cover: '', coverImg: '', cover_img: '', gallery: [], galleryJson: '[]' })
  }
  const directory = key.slice(0, key.lastIndexOf('/') + 1)
  const images = Array.isArray(data.images) && data.images.length <= 6 ? data.images : []
  const gallery = images.filter(item => item && Number.isInteger(item.width) && Number.isInteger(item.height)
    && Math.max(item.width, item.height) >= 1600 && Math.min(item.width, item.height) >= 800)
    .map(item => item.key).filter(k => typeof k === 'string' && k.startsWith(directory) && manifestPath(k))
  return Object.assign({}, row, { photoCredit: data.credit, gallery: PM.galleryKeys(row).length ? PM.galleryKeys(row) : gallery })
}
/** 目录图片先读同源许可清单，再交给界面；许可未确认或清单不可读时不闪现照片。 */
function resolvePlaceMedia(app, rows) {
  return Promise.all(rows.map(row => {
    const key = row && (row.cover || row.coverImg || row.cover_img)
    const url = manifestPath(key)
    if (!url || !PM.hasSeedTag(row)) return row
    let pending = inFlight.get(url)
    if (!pending) {
      pending = new Promise(resolve => {
        const timer = setTimeout(() => resolve(null), 4000)
        const done = data => { clearTimeout(timer); resolve(data) }
        try { app.sendRequest({ url, method: 'GET', auth: false, hideLoading: true, silentError: true,
          success: res => done(res && Number(res.code) === 200 ? res.data : null),
          successStatusAbnormal: () => done(null), fail: () => done(null) }) }
        catch (_) { done(null) }
      })
      inFlight.set(url, pending)
      pending.then(() => { if (inFlight.get(url) === pending) inFlight.delete(url) })
    }
    return pending.then(data => applyManifest(row, data))
  }))
}
module.exports = { manifestPath, applyManifest, resolvePlaceMedia }
