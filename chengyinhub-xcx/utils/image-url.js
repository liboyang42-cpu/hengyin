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
 * 后端基址 = 本次运行 app.sendRequest 用的同一个 globalData.siteBaseUrl(测试/开发服可能是 http),
 * 只有完全没配时才回落正式域名;release 构建 siteBaseUrl 就是正式域名,行为不变。
 * 部署顺序兜底:后端没有 /api/common/img(小程序先于 jar 发布)或返回 404/403 时,
 * 改写过的 <image> 走 binderror="onImgRenditionError"(data-raw = 改写前地址),该文件本会话内退回原签名图,只换一次;
 * 本会话累计 BREAKER_LIMIT 个文件出错即熔断,之后不再改写任何图片。
 * 模板里直接拼的地方用 utils/wxs/img.wxs 的 fit(),规则与这里逐条对齐,tests/unit/image-url.test.js 做两边对拍。 */

const config = require('./config.js')

const OSS_HOST = 'chengyin-oss.oss-cn-shanghai.aliyuncs.com'
const DEFAULT_API_BASE = 'https://www.chengyinhub.com/prod-api'
/** 本会话缩略图出错多少个文件后熔断(之后全部用原图)。 */
const BREAKER_LIMIT = 3
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

function configuredBase(value) {
  return typeof value === 'string' && value.trim() ? value.trim().replace(/\/+$/, '') : ''
}

/** 与 app.sendRequest 同一个后端基址(任何协议);App 未就绪时按同一份运行配置解析;都没有才用正式域名。 */
function apiBase() {
  try {
    const app = typeof getApp === 'function' ? getApp() : null
    const g = app && app.globalData
    const base = g && (configuredBase(g.siteBaseUrl) || configuredBase(g.runtimeConfig && g.runtimeConfig.apiBaseUrl))
    if (base) return base
  } catch (e) { /* App 未就绪,往下按运行配置解析 */ }
  try {
    if (typeof wx !== 'undefined') {
      const base = configuredBase(config.resolveFromWx(wx).apiBaseUrl)
      if (base) return base
    }
  } catch (e) { /* 取不到运行配置 */ }
  return DEFAULT_API_BASE
}

// ---- 会话级出错记录:文件名(32hex.ext) → true;累计 BREAKER_LIMIT 个即熔断 ----
let failed = {}
let failCount = 0
let tripped = false

function secure(src) {
  return String(src).trim().replace(OWN_HTTP, 'https://$1$2')
}

/** 能被改写的本桶签名原图 → 文件名;其余 ''。 */
function renditionKey(src) {
  if (typeof src !== 'string' || !src) return ''
  const m = secure(src).match(SIGNED_OSS)
  return m && signedQuery(m[2]) ? m[1] : ''
}

/** 这个 ctx 渲染时有没有把该文件改写成缩略地址(与 img.wxs fit() 同口径)。 */
function rewrittenUnder(ctx, key) {
  return !!(ctx && ctx.base && !ctx.off && !(ctx.skip && ctx.skip[key]))
}

/**
 * 一张改写过的图加载失败:记下该文件(本会话后续都用原图),计数到 BREAKER_LIMIT 熔断。
 * @param {string} raw 改写前的原地址(模板 data-raw)
 * @param {object} ctx 出错时页面正在用的 imgCtx
 * @returns 需要换上的新 imgCtx;原图本身也坏 / 本来就没改写 → null(不再换,杜绝循环)
 */
function noteRenditionError(raw, ctx) {
  const key = renditionKey(raw)
  if (!key || !rewrittenUnder(ctx, key)) return null
  if (!failed[key]) {
    failed[key] = true
    failCount += 1
    if (failCount >= BREAKER_LIMIT) tripped = true
  }
  return wxsContext()
}

/** 页面 / 组件共用的 binderror 处理:Page({ onImgRenditionError: imageUrl.onImgRenditionError }) 或放进组件 methods。 */
function onImgRenditionError(e) {
  const ds = e && e.currentTarget && e.currentTarget.dataset
  if (!ds || !this || typeof this.setData !== 'function') return
  const next = noteRenditionError(ds.raw, this.data && this.data.imgCtx)
  if (next) this.setData({ imgCtx: next })
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
  const url = secure(src)
  const m = url.match(SIGNED_OSS)
  if (!m || !signedQuery(m[2])) return url
  if (tripped || failed[m[1]]) return url
  const width = renditionWidth(slot, o.dpr)
  if (!width) return url
  const base = o.base ? String(o.base).replace(/\/+$/, '') : apiBase()
  return base + '/api/common/img/' + width + '/' + (o.webp ? 'webp' : 'orig') + '/upload/' + m[1] + '?' + m[2]
}

/** 给 img.wxs fit() 用的上下文:模板没有 getApp / wx,由页面放进 data。 */
function wxsContext() {
  return { base: apiBase(), dpr: pixelRatio(), off: tripped, skip: Object.assign({}, failed) }
}

function breakerTripped() { return tripped }

function resetForTest() { cachedDpr = 0; failed = {}; failCount = 0; tripped = false }

module.exports = {
  sized, renditionWidth, wxsContext, renditionKey, noteRenditionError, onImgRenditionError, breakerTripped,
  SLOTS, WIDTHS, OSS_HOST, BREAKER_LIMIT, DEFAULT_API_BASE, resetForTest
}
