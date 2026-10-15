/**
 * utils/location/last-location.js —— 定位兜底:上次成功位置 → 固定坐标
 *
 * 取代页面里写死的上海坐标。只存经纬度(不含地址/时间序列),仅在用户主动定位成功后写入。
 * resolve() 永远返回可用坐标,并用 located=false 告诉页面显示「未获取定位」提示。
 */
const KEY = 'cy_last_location'
const FIXED = { latitude: 31.230416, longitude: 121.473701 }

function valid(p) {
  return !!p && Number.isFinite(Number(p.latitude)) && Number.isFinite(Number(p.longitude))
    && Math.abs(Number(p.latitude)) <= 90 && Math.abs(Number(p.longitude)) <= 180
    && !(Number(p.latitude) === 0 && Number(p.longitude) === 0)
}

function load(env) {
  const w = env || (typeof wx !== 'undefined' ? wx : null)
  try {
    const v = w && w.getStorageSync(KEY)
    return valid(v) ? { latitude: Number(v.latitude), longitude: Number(v.longitude) } : null
  } catch (e) { return null }
}

function save(res, env) {
  const w = env || (typeof wx !== 'undefined' ? wx : null)
  if (!valid(res)) return false
  try {
    w.setStorageSync(KEY, { latitude: Number(res.latitude), longitude: Number(res.longitude) })
    return true
  } catch (e) { return false }
}

/** @returns {{latitude:number, longitude:number, source:'last'|'fixed', hint:string}} */
function resolve(env) {
  const last = load(env)
  if (last) return Object.assign({ source: 'last', hint: '未获取定位，当前显示上次所在位置' }, last)
  return Object.assign({ source: 'fixed', hint: '未获取定位，当前显示默认位置' }, FIXED)
}

module.exports = { KEY, FIXED, valid, load, save, resolve }
