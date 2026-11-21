'use strict'
/* 图片按显示槽位出图(2026-11-21 负责人 DevTools 走查:详情封面拉 OSS 原图,黑骨架停 5-10 秒)。
 *
 * 玩家页的图都是本桶 v1 签名原图(upload/<32hex>.jpg?Expires&OSSAccessKeyId&Signature)。
 * x-oss-process 是签名子资源,客户端直接拼会 403,所以改走后端
 *   GET {apiBase}/api/common/img/{宽}/{orig|webp}/upload/<文件>?<原签名查询串>
 * 后端重算原签名,只有本存储签出、未过期的原图才 302 到带缩放参数的新签名(见 ApiCommonController#imageRendition)。
 *
 * 用法:sized(src, 'hero') / sized(src, 'card', { webp: true }) / sized(src, 120)(CSS px)
 *  - 槽位宽 × DPR 向上取到固定档位(与后端 IMAGE_RENDITION_WIDTHS 同一组),同图变体有限,缓存可命中;
 *  - webp 只在 <image webp> 的位置开(iOS 不带 webp 属性不解码),PNG 透明在两种格式下都保留;
 *  - 本地图、外域图、未签名图、已带处理参数的图、GIF 等一律原样返回(只做自有域名 http→https)。
 * 模板里直接拼的地方用 utils/wxs/img.wxs 的 fit(),规则与这里逐条对齐,tests/unit/image-url.test.js 做两边对拍。 */

const OSS_HOST = 'chengyin-oss.oss-cn-shanghai.aliyuncs.com'
const DEFAULT_API_BASE = 'https://www.chengyinhub.com/prod-api'
/** 与后端 FileOssUploadUtils.IMAGE_RENDITION_WIDTHS 一致;改这里要同步改后端。 */
const WIDTHS = [160, 240, 320, 480, 640, 750, 960, 1080, 1280]

/** 显示槽位 → CSS 像素宽(按 375 宽设计稿;更宽的屏靠 DPR 档位向上取兜住)。 */
const SLOTS = {
  hero: 375,      // 详情页首屏封面 / 画廊
  card: 343,      // 首页、发现整宽卡;站点大图;剧情流配图
  half: 172,      // 双列卡、首页附近卡、章节卡
  thumb: 96,      // 列表缩略图、完成页照片
  avatar: 48,     // 头像、小圆图
  preview: 1280   // 全屏预览(取最大档)
}

const OWN_HTTP = /^http:\/\/(chengyin-oss\.oss-cn-shanghai\.aliyuncs\.com|www\.chengyinhub\.com)(\/)/i
const SIGNED_OSS = /^https:\/\/chengyin-oss\.oss-cn-shanghai\.aliyuncs\.com\/upload\/([0-9a-f]{32}\.(?:jpg|jpeg|png))\?([^#]*)$/

let cachedDpr = 0

function pixelRatio() {
  if (cachedDpr) return cachedDpr
  let ratio = 2
  try {
    const info = typeof wx !== 'undefined' && wx.getWindowInfo ? wx.getWindowInfo() : null
    if (info && info.pixelRatio > 0) ratio = info.pixelRatio
  } catch (e) { /* 取不到按 2 倍屏 */ }
  cachedDpr = Math.min(3, Math.max(1, ratio))
  return cachedDpr
}

function apiBase() {
  try {
    const app = typeof getApp === 'function' ? getApp() : null
    const base = app && app.globalData && app.globalData.siteBaseUrl
    if (typeof base === 'string' && /^https:\/\/[^/?#]+/i.test(base)) return base.replace(/\/+$/, '')
  } catch (e) { /* App 未就绪时用正式域名 */ }
  return DEFAULT_API_BASE
}

/** 槽位名或 CSS 宽 → 档位像素宽。 */
function renditionWidth(slot, dpr) {
  const css = typeof slot === 'number' ? slot : SLOTS[slot]
  if (!(css > 0)) return 0
  const px = Math.ceil(css * (dpr || pixelRatio()))
  for (let i = 0; i < WIDTHS.length; i++) if (WIDTHS[i] >= px) return WIDTHS[i]
  return WIDTHS[WIDTHS.length - 1]
}

/** 只认恰好 Expires / OSSAccessKeyId / Signature 三个参数的 v1 签名;多参(含已带 x-oss-process)不动。 */
function signedQuery(query) {
  const pairs = String(query || '').split('&')
  if (pairs.length !== 3) return false
  const seen = {}
  for (let i = 0; i < pairs.length; i++) {
    const at = pairs[i].indexOf('=')
    if (at <= 0 || at === pairs[i].length - 1) return false
    const name = pairs[i].slice(0, at)
    if (name !== 'Expires' && name !== 'OSSAccessKeyId' && name !== 'Signature') return false
    if (seen[name]) return false
    seen[name] = true
  }
  return true
}

/**
 * @param {string} src 后端给的图片地址
 * @param {string|number} slot 槽位名(SLOTS)或 CSS 宽
 * @param {{ webp?: boolean, dpr?: number, base?: string }} [opts]
 */
function sized(src, slot, opts) {
  if (typeof src !== 'string' || !src) return src
  const o = opts || {}
  const url = src.trim().replace(OWN_HTTP, 'https://$1$2')
  const m = url.match(SIGNED_OSS)
  if (!m || !signedQuery(m[2])) return url
  const width = renditionWidth(slot, o.dpr)
  if (!width) return url
  const base = o.base ? String(o.base).replace(/\/+$/, '') : apiBase()
  return base + '/api/common/img/' + width + '/' + (o.webp ? 'webp' : 'orig') + '/upload/' + m[1] + '?' + m[2]
}

/** 给 img.wxs fit() 用的上下文:模板没有 getApp / wx,由页面放进 data。 */
function wxsContext() {
  return { base: apiBase(), dpr: pixelRatio() }
}

function resetForTest() { cachedDpr = 0 }

module.exports = { sized, renditionWidth, wxsContext, SLOTS, WIDTHS, OSS_HOST, resetForTest }
