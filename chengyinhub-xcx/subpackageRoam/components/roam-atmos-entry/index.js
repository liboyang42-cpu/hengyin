'use strict'

// 第九轮 m1 第 8 项:首次进漫游的大气层转场(m3 出片,地址在 utils/config.js roamAtmosVideoUrl,空则不播)。
//   · 每台设备只播一次:真正开播或用户点跳过才记「已看过」;没播成(慢网 / 失败 / 超时)下次还可以再试。
//   · 不挡地图:页面先把地图铺好再挂本组件;加载中整层透明,START_TIMEOUT_MS 内没开播就放弃。
//   · 减弱动态、2G/3G/无网、地址不是受信 https 都直接放弃,不提示。
//   · app 想重播时调 resetSeen()(或给 force=true)。
const SEEN_KEY = 'cy_roam_atmos_seen_v1'
const START_TIMEOUT_MS = 1500
const MAX_PLAY_MS = 6000
const FADE_MS = 600
const DIM_MS = 360
const SLOW_NETWORKS = ['2g', '3g', 'none', 'unknown']

function trusted(src) {
  try {
    const config = require('../../../utils/config.js')
    return typeof config.isTrustedAssetUrl === 'function' ? config.isTrustedAssetUrl(src) : /^https:\/\//.test(String(src || ''))
  } catch (e) { return false }
}
function readSeen() { try { return !!wx.getStorageSync(SEEN_KEY) } catch (e) { return true } }
function markSeen() { try { wx.setStorageSync(SEEN_KEY, 1) } catch (e) { /* 存不下就当没看过,下次再说 */ } }
function resetSeen() { try { wx.removeStorageSync(SEEN_KEY) } catch (e) { /* 无副作用 */ } }

Component({
  properties: {
    src: { type: String, value: '' },
    reducedMotion: { type: Boolean, value: false },
    force: { type: Boolean, value: false },
  },
  data: { phase: 'off' },
  lifetimes: {
    attached() { this._start() },
    detached() { this._clear(); this._dead = true },
  },
  pageLifetimes: {
    hide() { if (this.data.phase !== 'off') this._finish(false) },
  },
  methods: {
    _clear() {
      ;['_startTimer', '_maxTimer', '_fadeTimer', '_dimTimer'].forEach((k) => { if (this[k]) { clearTimeout(this[k]); this[k] = null } })
    },
    _start() {
      const src = this.data.src
      if (!src || !trusted(src) || this.data.reducedMotion || (!this.data.force && readSeen())) { this._done('skip'); return }
      const go = () => {
        if (this._dead) return
        this.setData({ phase: 'load' })
        this._startTimer = setTimeout(() => this._finish(false), START_TIMEOUT_MS)
      }
      try {
        wx.getNetworkType({
          success: (r) => { if (SLOW_NETWORKS.indexOf(String(r && r.networkType || 'unknown').toLowerCase()) >= 0) this._done('slow'); else go() },
          fail: () => this._done('slow'),
        })
      } catch (e) { this._done('slow') }
    },
    onPlay() {
      if (this.data.phase !== 'load') return
      if (this._startTimer) { clearTimeout(this._startTimer); this._startTimer = null }
      markSeen()
      this.setData({ phase: 'play' })
      this._maxTimer = setTimeout(() => this._finish(true), MAX_PLAY_MS)
    },
    onEnded() { this._finish(true) },
    onError() { this._finish(false) },
    skip() {
      if (this.data.phase === 'off' || this.data.phase === 'fade' || this.data.phase === 'dim') return
      markSeen()
      this._finish(true)
    },
    // played=true:播过(或用户跳过)⇒ 淡出;false:没播成 ⇒ 直接撤,不留黑屏
    _finish(played) {
      if (this._dead || this.data.phase === 'off' || this.data.phase === 'fade' || this.data.phase === 'dim') return
      this._clear()
      if (!played || this.data.phase === 'load') { this.setData({ phase: 'off' }); this._done(played ? 'skip' : 'fail'); return }
      // 先压暗成黑(片尾近白),再整层淡出
      this.setData({ phase: 'dim' })
      this._dimTimer = setTimeout(() => {
        if (this._dead) return
        this.setData({ phase: 'fade' })
        this._fadeTimer = setTimeout(() => { if (!this._dead) { this.setData({ phase: 'off' }); this._done('played') } }, FADE_MS)
      }, DIM_MS)
    },
    _done(reason) { this.triggerEvent('done', { reason }) },
  },
})

module.exports = { SEEN_KEY, resetSeen, START_TIMEOUT_MS, DIM_MS, FADE_MS }
