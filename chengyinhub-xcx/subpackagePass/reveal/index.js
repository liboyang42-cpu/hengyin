// 通行证 · 钥匙开场(l-icons 2026-11-11,业主批准 proto/icons ps-key,逐镜对照 satya2 时间轴)。
// 画面 = 预渲染序列帧(frames.js,本分包内)+ 活的 DOM 层(底部暖橙渐变、眉题 / 标题 / 一行正文 / 按钮、右上「跳过」)。
// 完整版只在第一次进入(本机本账号记一次)和开通成功(mode=paid)时播;之后进来直接是待机循环 + 文字 + 按钮。
// 减少动态效果:直接停在 4.6s 的终态(待机第 0 帧 + 渐变 + 文字 + 按钮)。商家账号没有通行证:整页只给一句原因。
// 文案里的数字只来自上一页带来的服务端事实(count / until),没有就不写那一行。
const app = getApp()
const { readReducedMotion } = require('../../utils/motion-preference.js')
const roleGuard = require('../../utils/roleGuard.js')
const FR = require('./frames.js')

const SEEN_KEY = 'pass_reveal_seen_v1'
// DOM 层时间轴(ms,与 proto key3d.js TL 一致):渐变 3000、眉题 3400、标题 3550、正文 3750、按钮 4050;「跳过」提前到 600 让人真能跳过等待
const TL = { grad: 3000, eb: 3400, h: 3550, p: 3750, btn: 4050, skip: 600 }
const MEMBERSHIP = '/subpackageMember/market/membership/index'
const LIBRARY = '/subpackageMember/market/library/index'

function seenBefore() {
  try {
    const v = wx.getStorageSync(SEEN_KEY)
    const uid = String(app.getUserID ? app.getUserID() || '' : '')
    return !!(v && v.uid === uid)
  } catch (e) { return false }
}
function markSeen() {
  try { wx.setStorageSync(SEEN_KEY, { uid: String(app.getUserID ? app.getUserID() || '' : ''), at: Date.now() }) } catch (e) { /* 记不上就下次再播一遍 */ }
}

// D-116(业主 2026-11-11「加预下载」):弱网下首帧不能是黑的。进页先把序列帧 WebP 解码进图片缓存(getImageInfo,帧图本来就在本分包内,不增主包):
// 完整版 intro 前 3 张 + 待机第 0 张立刻拉,其余 intro / idle 排在后面低并发补;失败一律吞掉(播放器照旧按需挂图)。
const WARM_FIRST = 3
function warmFrames(list, done) {
  const queue = list.slice()
  const pump = () => {
    const src = queue.shift()
    if (!src) return
    if (typeof wx.getImageInfo !== 'function') return
    wx.getImageInfo({ src, complete: () => { if (done) done(src); pump() } })
  }
  pump(); pump()
}

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight,
    navBarHeight: app.globalData.navBarHeight,
    fr: FR,
    merchant: false,
    reduced: false,
    full: false,        // 这次播不播完整版
    introOn: false,     // 完整版序列帧层
    idleOn: false,      // 待机循环层
    step: { grad: false, eb: false, h: false, p: false, btn: false, skip: false },
    copy: { eb: '通行证', h: '赢取你的钥匙', p: '持证期间，主题库里的城市定向随时开玩。', btn: '开始' }
  },
  onLoad(options) {
    const o = options || {}
    let reduced = false
    try { reduced = !!readReducedMotion() } catch (e) { reduced = false }
    let merchant = false
    try { merchant = !!roleGuard.isMerchantView() } catch (e) { merchant = false }
    const paid = o.mode === 'paid'
    const copy = paid ? this._paidCopy(o) : this.data.copy
    this._paid = paid // 只决定「开始」跳哪页,不渲染,不走 setData(U4)
    this.setData({ reduced, merchant, copy })
    if (merchant) return
    const full = !reduced && (paid || !seenBefore())
    this._warm(full)
    if (!seenBefore()) markSeen()
    if (!full) { this._end(); return }
    this.setData({ full: true, introOn: true })
    const t = (ms, fn) => { (this._t = this._t || []).push(setTimeout(() => { if (!this._unloaded) fn() }, ms)) }
    t(TL.skip, () => this.setData({ 'step.skip': true }))
    t(TL.grad, () => this.setData({ 'step.grad': true }))
    t(TL.eb, () => this.setData({ 'step.eb': true }))
    t(TL.h, () => this.setData({ 'step.h': true }))
    t(TL.p, () => this.setData({ 'step.p': true }))
    t(TL.btn, () => this.setData({ 'step.btn': true }))
  },
  _warm(full) {
    const src = (a) => a.map((x) => x.src)
    const intro = src(FR.intro), idle = src(FR.idle)
    const order = full
      ? intro.slice(0, WARM_FIRST).concat(idle.slice(0, 1), intro.slice(WARM_FIRST), idle.slice(1))
      : idle.slice(0, 2).concat(idle.slice(2))
    warmFrames(order)
  },
  onUnload() { this._unloaded = true; (this._t || []).forEach(clearTimeout) },
  /** 开通成功:眉题 / 标题 / 正文只写上一页带来的服务端事实 */
  _paidCopy(o) {
    const count = /^\d+$/.test(String(o.count || '')) ? String(o.count) : ''
    const until = o.until ? decodeURIComponent(String(o.until)) : ''
    return {
      eb: '通行证 · 已开通',
      h: count ? count + ' 个主题，全部可玩' : '已开通',
      p: until ? '到 ' + until + ' · 价格都是 ¥0' : '',
      btn: '进入可玩主题库'
    }
  },
  /** 终态:待机循环 + 渐变 + 全部文字 + 按钮(完整版播完、减少动态效果、非首次进入都落到这里) */
  _end() {
    (this._t || []).forEach(clearTimeout)
    this.setData({ idleOn: true, step: { grad: true, eb: true, h: true, p: true, btn: true, skip: false } })
    // 待机层淡入盖住完整版的最后一帧后,再把完整版层拆掉
    const ms = this.data.reduced ? 0 : 420
    this._endT = setTimeout(() => { if (!this._unloaded) this.setData({ introOn: false }) }, ms)
  },
  onIntroEnd() { if (!this._unloaded) this._end() },
  onSkip() { this._go() },
  onStart() { this._go() },
  _go() {
    if (this._navigating) return
    this._navigating = true
    clearTimeout(this._endT)
    const url = this._paid ? LIBRARY : MEMBERSHIP
    wx.redirectTo({ url, fail: () => { this._navigating = false } })
  },
  goBack() {
    if (getCurrentPages().length > 1) wx.navigateBack()
    else wx.switchTab({ url: '/pages/member/index/index' })
  }
})
