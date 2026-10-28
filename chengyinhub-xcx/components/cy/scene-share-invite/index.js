'use strict'

const mediaPreview = require('../../../utils/media-preview.js')
const { readPlayerCode } = require('../../../utils/player-code.js'); const { readReducedMotion } = require('../../../utils/motion-preference.js')

// G-6(2026-10-27 业主 p102「二维码没有」):码图迟迟不回 / 回来是空白图时,不再留一块空灰框,改出「重新生成」。
const QR_LOAD_TIMEOUT_MS = 10000
const QR_MIN_EDGE_PX = 20

Component({
  properties: {
    theme: { type: String, value: 'player' },
    qrUrl: { type: String, value: '' },
    // 设计稿二维码只用于 develop 环境的视觉验收,正式环境必须由调用方传入真实地址。
    designPreview: { type: Boolean, value: true },
    // BL-71:由组件自己读本人的个人小程序码(/api/user/player-code);调用方显式传 qrUrl 时以 qrUrl 为准。
    playerCode: { type: Boolean, value: false },
  },

  data: {
    reducedMotion: false,
    qrSrc: '',
    qrState: 'empty',
    qrErrorText: '',
  },

  observers: {
    'qrUrl, designPreview, playerCode': function () {
      this.syncQr()
    },
  },

  lifetimes: {
    attached() {
      this.setData({ reducedMotion: readReducedMotion() })
      this.syncQr()
    },
    detached() {
      this._codeEpoch = (this._codeEpoch || 0) + 1
      this._codeLoading = false
      this.clearQrTimer()
    },
  },

  methods: {
    syncQr() {
      // observer 与 attached 首开可能各调一次:个人码请求进行中就不再连发,避免后端生成锁回「服务繁忙」。
      if (!this.data.qrUrl && this.data.playerCode && this._codeLoading) return
      this._codeEpoch = (this._codeEpoch || 0) + 1
      if (!this.data.qrUrl && this.data.playerCode) {
        this.loadPlayerCode()
        return
      }
      this._codeLoading = false
      let canPreview = false
      try {
        const app = getApp()
        canPreview = !!(app && typeof app.isDevEnv === 'function' && app.isDevEnv())
      } catch (_) {}
      const source = this.data.qrUrl || (this.data.designPreview && canPreview ? '/images/figma-invite-qr.png' : '')
      this.setData({ qrSrc: source, qrState: source ? 'loading' : 'empty', qrErrorText: '' })
      if (source) this.armQrTimer()
    },

    // 图片加载超时兜底:同一张图到点还没 load/error,按加载失败处理
    armQrTimer() {
      this.clearQrTimer()
      const src = this.data.qrSrc
      this._qrTimer = setTimeout(() => {
        this._qrTimer = null
        if (this.data.qrState === 'loading' && this.data.qrSrc === src) this.setData({ qrState: 'error' })
      }, QR_LOAD_TIMEOUT_MS)
    },

    clearQrTimer() {
      if (this._qrTimer) { clearTimeout(this._qrTimer); this._qrTimer = null }
    },

    // 生成失败显示后端文案(如「个人码生成服务繁忙，请稍后重试」/「请先登录」),可重试。
    loadPlayerCode() {
      const that = this
      const epoch = this._codeEpoch
      this._codeLoading = true
      this.setData({ qrSrc: '', qrState: 'loading', qrErrorText: '' })
      const settle = (src, errorText) => {
        if (epoch !== that._codeEpoch) return
        that._codeLoading = false
        if (src) { that.setData({ qrSrc: src, qrState: 'loading', qrErrorText: '' }); that.armQrTimer() }
        else that.setData({ qrSrc: '', qrState: 'code-error', qrErrorText: errorText })
      }
      getApp().sendRequest({
        url: '/api/user/player-code',
        method: 'POST',
        hideLoading: true,
        silentError: true,
        success: function (res) {
          const src = readPlayerCode(res)
          settle(src, (res && res.msg) || '个人码暂时没有生成，请重试')
        },
        fail: function () { settle('', '网络异常，个人码加载失败') },
        successStatusAbnormal: function () { settle('', '个人码暂时没有生成，请重试') },
      })
    },

    // 点开预览;微信原生预览长按即可保存到相册。
    previewQr() {
      if (this.data.playerCode && this.data.qrState === 'ready' && this.data.qrSrc) mediaPreview.open([this.data.qrSrc], this.data.qrSrc)
    },

    isCurrentQrEvent(event) {
      return !!event && event.currentTarget && event.currentTarget.dataset
        && event.currentTarget.dataset.src === this.data.qrSrc
    },

    onQrLoad(event) {
      if (!this.isCurrentQrEvent(event)) return
      this.clearQrTimer()
      // 接口回了一张几像素的空白图也算失败,否则就是那块空灰框
      const size = (event && event.detail) || {}
      const blank = (size.width > 0 && size.width < QR_MIN_EDGE_PX) || (size.height > 0 && size.height < QR_MIN_EDGE_PX)
      this.setData({ qrState: blank ? 'error' : 'ready' })
    },

    onQrError(event) {
      if (!this.isCurrentQrEvent(event)) return
      this.clearQrTimer()
      this.setData({ qrState: 'error' })
    },

    retryQr() {
      if (this.data.qrState === 'code-error' || !this.data.qrSrc) {
        this.syncQr()
        return
      }
      // 个人码:图片坏了就重新生成一张;其余 error 分支已卸载 image,切回 loading 会重新创建并加载。
      if (this.data.playerCode && !this.data.qrUrl) { this.syncQr(); return }
      this.setData({ qrState: 'loading' })
      this.armQrTimer()
    },
  },
})
