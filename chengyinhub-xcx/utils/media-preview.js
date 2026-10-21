/**
 * utils/media-preview.js —— wx.previewImage 的统一入口
 *
 * 不传 current 时微信默认预览 urls[0]:多图列表里点第 3 张却从第 1 张看起。
 * 这里统一补 current,并过滤空串/非字符串,避免把 undefined 塞给 wx.previewImage 报错。
 */

function clean(urls) {
  return (Array.isArray(urls) ? urls : [urls]).filter((u) => typeof u === 'string' && u)
}

/**
 * @param {string|string[]} urls 预览图列表
 * @param {string|number} [current] 当前图 URL;传数字则按下标取
 * @returns {boolean} 是否真的调起了预览
 */
function open(urls, current) {
  const list = clean(urls)
  if (!list.length) return false
  let cur = typeof current === 'number' ? list[current] : current
  if (typeof cur !== 'string' || list.indexOf(cur) < 0) cur = list[0]
  wx.previewImage({ current: cur, urls: list })
  return true
}

module.exports = { open, clean }
