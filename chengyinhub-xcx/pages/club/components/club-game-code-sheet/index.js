'use strict'
const modal = require('../../../../utils/modal.js');
const toast = require('../../../../utils/toast.js');
/* 出示团码 · T1 底部弹层(Figma s7SEFaoJ3GQUIxJhdqcFUb / 335:1275)
 *
 * 为什么要有「保存」:有些玩法需要把码**打印**出来贴在站点上,现场靠纸质码核销。
 * 所以这不是锦上添花的分享按钮,是这张稿存在的理由。
 *
 * ⚠️ 保存到相册是要授权、且会失败的动作,三种失败必须分开说,不能一句「保存失败」糊过去:
 *   · 用户拒过授权 → 只能引导去设置里开,自己 authorize 再问也不会弹
 *   · 下载失败     → 网络问题,可以重试
 *   · 写相册失败   → 空间/系统权限,重试通常没用
 */
Component({
  properties: {
    show: { type: Boolean, value: false },
    title: { type: String, value: '出示团码' },
    name: { type: String, value: '' },          // 主题名,稿 335:1286
    qr: { type: String, value: '' },            // 服务端出的码图 URL
    state: { type: String, value: 'loading' },  // loading | ready | error
    errorText: { type: String, value: '' },
    countdownText: { type: String, value: '' }, // X034:码刷新倒计时,仅出码态由宿主传入
  },
  // 防重复点击的锁,wxml 不渲染 —— 放 data 里每次点保存都白做一次
  // 逻辑层→视图层传输(U4 判的正是这个)。
  attached() { this._saving = false },

  methods: {
    onClose() { this.triggerEvent('close') },
    onRetry() { this.triggerEvent('retry') },

    onSave() {
      if (this._saving || this.data.state !== 'ready') return
      const qr = this.data.qr
      if (!qr) return
      const that = this
      const isCurrent = () => that.data.show !== false && that.data.state === 'ready' && that.data.qr === qr
      this._saving = true
      if (qr.indexOf('data:') === 0) {
        const inline = /^data:image\/png;base64,([A-Za-z0-9+/]+={0,2})$/.exec(qr)
        if (!inline || inline[1].length % 4 !== 0 || inline[1].indexOf('iVBORw0KGgo') !== 0) {
          this._saving = false
          toast('码图没下载下来，请重试')
          return
        }
        const files = wx.getFileSystemManager()
        const filePath = wx.env.USER_DATA_PATH + '/cy-group-code-' + Date.now() + '-' + Math.random().toString(36).slice(2) + '.png'
        const cleanup = () => files.unlink({ filePath, fail() {} })
        files.writeFile({
          filePath, data: inline[1], encoding: 'base64',
          success() {
            if (!isCurrent()) { that._saving = false; cleanup(); return }
            that.saveToAlbum(filePath, cleanup)
          },
          fail() {
            that._saving = false
            cleanup()
            toast('码图没下载下来，请重试')
          },
        })
        return
      }
      wx.downloadFile({
        url: qr,
        success(res) {
          if (!isCurrent()) { that._saving = false; return }
          if (res.statusCode !== 200 || !res.tempFilePath) {
            that._saving = false
            toast('码图没下载下来，请重试')
            return
          }
          that.saveToAlbum(res.tempFilePath)
        },
        fail() {
          that._saving = false
          toast('码图没下载下来，请重试')
        },
      })
    },

    saveToAlbum(filePath, cleanup) {
      const that = this
      wx.saveImageToPhotosAlbum({
        filePath,
        success() {
          that._saving = false
          toast('已存到相册，可打印后贴在站点')
          that.triggerEvent('saved')
        },
        fail(err) {
          that._saving = false
          const msg = String((err && err.errMsg) || '')
          // 用户拒过授权:再调 authorize 不会弹窗,只能引导去设置页
          if (msg.indexOf('auth deny') >= 0 || msg.indexOf('authorize') >= 0) {
            modal.show({
              title: '需要相册权限',
              content: '保存游戏码需要相册权限。去设置里打开「保存到相册」后再试。',
              confirmText: '去设置',
              success(r) { if (r.confirm) wx.openSetting({}) },
            })
            return
          }
          if (msg.indexOf('cancel') >= 0) return
          toast('没能存进相册，检查存储空间后再试')
        },
        complete: cleanup,
      })
    },
  },
})
