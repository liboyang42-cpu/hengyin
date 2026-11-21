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

/** 只认能直接显示的地址。media-asset:N 是作者素材引用(DB 原值,如路线 img_url='media-asset:756'),
 *  客户端解不了,放进 <image> 只会触发 binderror 再被清成空 —— 当它不存在,链往下走。 */
function isDisplayable(src) {
  return typeof src === 'string' && !!src && !/^media-asset:/i.test(src)
}

function firstImage(value) {
  return parseImageList(value).filter(isDisplayable)[0] || ''
}

/** 章节故事块里的第一张图(块 { type:'image', url })。 */
function firstBlockImage(blocks) {
  if (!Array.isArray(blocks)) return ''
  for (let i = 0; i < blocks.length; i++) {
    const b = blocks[i]
    if (b && b.type === 'image' && isDisplayable(b.url)) return b.url
  }
  return ''
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

/** 章节 id → 候选图数组:img_arr → cover → 故事块首图(章节列表可能为空或不是数组;引用值由 resolve 过滤)。 */
function chapterImagesById(chapters) {
  const map = {}
  if (!Array.isArray(chapters)) return map
  chapters.forEach((c) => {
    if (!c) return
    const id = c.chapterId != null ? c.chapterId : c.id
    if (id == null) return
    const block = firstBlockImage(c.blocks)
    map[String(id)] = parseImageList(c.imgArr != null ? c.imgArr : c.img_arr)
      .concat(parseImageList(c.cover)).concat(block ? [block] : [])
  })
  return map
}

/** 页面上这一站所在章节(已签名的 chapterCards / chapter)的图:章节封面 → 该章第一张故事块图。 */
function pageChapterImage(data, node) {
  const d = data || {}
  const cid = node && node.chapterId != null ? String(node.chapterId) : ''
  const cards = Array.isArray(d.chapterCards) ? d.chapterCards : []
  const chapter = d.chapter || null
  const card = cid ? cards.find((c) => c && c.id != null && String(c.id) === cid) : null
  // 当前章:号对上,或站点没章节号 / 整条路线只有一章
  const isCurrent = !!chapter && (!cid || cards.length <= 1 || (chapter.id != null && String(chapter.id) === cid))
  const own = [card, isCurrent ? chapter : null].filter(Boolean)
  for (let i = 0; i < own.length; i++) {
    const src = firstImage(own[i].cover) || firstBlockImage(own[i].blocks)
    if (src) return src
  }
  return ''
}

/**
 * 用页面上已解析(签名)的地址取站点图:站点自己的图 → 本章图(chapterImgs[章节号],即 /nodes 章节 img_arr → cover → 故事块首图;
 * 再退页面 chapterCards / chapter 的封面 → 该章第一张故事块图) → 路线封面(topicCover)。
 * 从不返回 media-asset: 引用;全空 = '' → 模板回插图 / 纹理底。
 * @param {{ chapterCards?: any[], chapter?: object, topicCover?: any, chapterImgs?: object }} view 页面 data 的这几项
 * @param {object} node 归一后的站点(imgUrl / chapterId)
 */
function resolvePageStationImage(view, node) {
  const v = view || {}
  const n = node || {}
  const cid = n.chapterId != null ? String(n.chapterId) : ''
  return firstImage(n.imgUrl)
    || (cid && v.chapterImgs ? firstImage(v.chapterImgs[cid]) : '')
    || pageChapterImage(v, n)
    || firstImage(v.topicCover)
}

/**
 * 站点列表按 resolvePageStationImage 重算 photo(原地写,返回同一数组)。gamePhoto 是玩法自己的封面时保留,
 * 否则(空 / 与旧 photo 同源)跟着新的 photo 走。
 */
function fillStationPhotos(nodes, view) {
  if (!Array.isArray(nodes)) return nodes
  nodes.forEach((n) => {
    if (!n) return
    const prev = n.photo
    const src = resolvePageStationImage(view, n)
    n.photo = src
    if (!isDisplayable(n.gamePhoto) || n.gamePhoto === prev) n.gamePhoto = src
  })
  return nodes
}

module.exports = { parseImageList, firstImage, isDisplayable, firstBlockImage, resolveStationImage, resolveGameImage,
  chapterImagesById, resolvePageStationImage, fillStationPhotos }
