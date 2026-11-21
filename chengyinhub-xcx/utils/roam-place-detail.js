'use strict'

const PM = require('./roam-place-map.js')
const { resolvePlaceMedia } = require('./roam-place-media.js')
const { exactSessionId } = require('./roam-recovery.js')
const { normalizeTrack } = require('./roam-track-simplify.js')

function read(app, url, data) {
  return new Promise((resolve, reject) => app.sendRequest({ url, data, method: 'GET',
    hideLoading: true, success: resolve, successStatusAbnormal: resolve,
    fail: () => reject(new Error('地点暂时无法读取，请重试')) }))
}
async function readPlace(app, id) {
  const key = exactSessionId(id)
  if (!key) throw new Error('地点信息不完整')
  const res = await read(app, '/api/city/places/' + key)
  const row = res && Number(res.code) === 200 && res.data
  if (!row || exactSessionId(row.poiId) !== key || !PM.hasSeedTag(row)) throw new Error('地点未开放或暂时无法读取')
  const point = normalizeTrack([row])[0]
  if (!point) throw new Error('地点位置暂时无法读取')
  const rows = await resolvePlaceMedia(app, [Object.assign({}, row, point, { id: row.poiId, type: 1 })])
  return rows[0]
}
async function readStripPlace(app, item, nearby, center) {
  const matches = row => row && Number(row.type) === 1 && PM.parseTags(row.tags).includes(item.seed)
  let row = (nearby || []).find(matches)
  if (!row && center) {
    // 目录只有种子标签，没有数据库 ID；扩大公开读取范围后按标签精确对上，绝不按名称猜。
    const res = await read(app, '/api/roam/pois', { lat: center[0], lng: center[1], radius: 100000 })
    const got = res && Number(res.code) === 200 && PM.unwrapPois(res)
    row = got && got.rows.find(matches)
  }
  if (!row) throw new Error('这个地点暂时无法读取，请重试')
  const detail = await readPlace(app, row.id)
  if (!matches(detail)) throw new Error('地点信息未能核对，请重试')
  return detail
}
module.exports = { readPlace, readStripPlace }
