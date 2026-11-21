'use strict'
// 站点 / 站点玩法的配图兜底链(2026-11-21 业主「这个应该有图片」)。
// 168 个站点里 69 个没有 img_url,站点玩法(cms_member_template)都没有封面,
// 以前这些位置要么空着,要么是灰色人形剪影。现在统一按这条链取图,一个口径:
//   站点图 img_url → 本站所在章节的第一张图(章节 img_arr) → 路线封面(主题 imgUrl) → 深色纹理底(texture)。
// 玩法缩略图:玩法封面 → 同一条站点链。
// F07:后续站的站点图在到站前由服务端置空(imgHidden),这里自然落到章节图 / 路线封面 —— 这两张本来就在页面上公开显示,不会把站点照片提前露出来。
// 返回的 src 是原始首图;模板里再过 img.wxs 的 one() 把自有域名 http 升 https。

/** 图片字段统一成数组:逗号 / 分号分隔串、JSON 数组串、数组都认;坏数据返回空数组,不抛。 */
function parseImageList(value) {
  if (value == null) return []
  if (Array.isArray(value)) {
    return value.reduce((out, item) => out.concat(typeof item === 'string' ? splitPlain(item) : []), [])
  }
  if (typeof value !== 'string') return []
  const text = value.trim()
  if (!text) return []
  if (text.charAt(0) === '[') {
    let parsed = null
    try { parsed = JSON.parse(text) } catch (e) { parsed = null }
    if (Array.isArray(parsed)) return parseImageList(parsed)
    // 半截 JSON(如 `["a.jpg",`):剥掉括号引号后按普通分隔串取
    return splitPlain(text.replace(/[[\]"']/g, ''))
  }
  return splitPlain(text)
}

function splitPlain(text) {
  return String(text).split(/[,;]/).map((s) => s.trim()).filter((s) => s && s !== 'null' && s !== 'undefined')
}

function firstImage(value) {
  return parseImageList(value)[0] || ''
}

/**
 * @param {{ stationImg?: any, chapterImgArr?: any, topicCover?: any }} input
 * @returns {{ src: string, source: 'station'|'chapter'|'route'|'texture' }}
 */
function resolveStationImage(input) {
  const o = input || {}
  const station = firstImage(o.stationImg)
  if (station) return { src: station, source: 'station' }
  const chapter = firstImage(o.chapterImgArr)
  if (chapter) return { src: chapter, source: 'chapter' }
  const route = firstImage(o.topicCover)
  if (route) return { src: route, source: 'route' }
  return { src: '', source: 'texture' }
}

/**
 * @param {{ gameCover?: any, stationImg?: any, chapterImgArr?: any, topicCover?: any }} input
 * @returns {{ src: string, source: 'game'|'station'|'chapter'|'route'|'texture' }}
 */
function resolveGameImage(input) {
  const o = input || {}
  const game = firstImage(o.gameCover)
  if (game) return { src: game, source: 'game' }
  return resolveStationImage(o)
}

/** 章节 id → 章节 img_arr(原样,交给 resolve 解析);章节列表可能为空或不是数组。 */
function chapterImagesById(chapters) {
  const map = {}
  if (!Array.isArray(chapters)) return map
  chapters.forEach((c) => {
    if (!c) return
    const id = c.chapterId != null ? c.chapterId : c.id
    if (id == null) return
    map[String(id)] = c.imgArr != null ? c.imgArr : (c.img_arr != null ? c.img_arr : c.cover)
  })
  return map
}

module.exports = { parseImageList, firstImage, resolveStationImage, resolveGameImage, chapterImagesById }
