// 外部游戏详情(只读)。GET /api/external-game/catalog/{gameId}。
// 小程序里不运行外部游戏:游戏包是服务端签名的 JSON 归档(没有可托管的网页地址),web-view 也只能打开
// 已配置的业务域名,所以只展示信息并提示在城瘾 App 中游玩。签名下载地址(downloadPath)不使用、不展示。
const app = getApp()
const { isBizOk } = require('../../utils/response-shape.js')
const view = require('../utils/external-game-view.js')

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight,
    navBarHeight: app.globalData.navBarHeight,
    state: 'loading', // loading | ready | missing | error
    game: null,
  },
  onLoad(options) {
    const gameId = options && options.gameId ? decodeURIComponent(options.gameId) : ''
    if (!view.validGameId(gameId)) { this.setData({ state: 'missing' }); return }
    this._gameId = gameId
    this.load()
  },
  retry() { this.load() },
  load() {
    const that = this
    const seq = (this._seq = (this._seq || 0) + 1)
    this.setData({ state: 'loading' })
    app.sendRequest({
      url: '/api/external-game/catalog/' + encodeURIComponent(this._gameId), method: 'GET', hideLoading: true, silentError: true,
      success(res) {
        if (seq !== that._seq) return
        const game = isBizOk(res) ? view.normalizeGame(res.data) : null
        if (game) that.setData({ state: 'ready', game })
        else that.setData({ state: 'error' })
      },
      fail() { if (seq === that._seq) that.setData({ state: 'error' }) },
      // 404 = 已下架或不存在;其余(含 503 签名未配置)按读取失败可重试。
      successStatusAbnormal(body, status) {
        if (seq !== that._seq) return
        that.setData({ state: Number(status) === 404 ? 'missing' : 'error' })
      },
    })
  },
  goBack() {
    if (getCurrentPages().length > 1) wx.navigateBack()
    else wx.redirectTo({ url: '/subpackageMember/external-games/index' })
  },
})
