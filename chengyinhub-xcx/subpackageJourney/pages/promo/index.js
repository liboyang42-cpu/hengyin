const api = require('../../utils/journey-api.js');
const view = require('../../utils/journey-view.js');
const app = getApp();

/* P2 玩家推广中心：只读本人归因与分配明细。应计、可结算、已标付款、冲销分开；
 * 玩家没有提现通道(payout.available=false)时不出提现按钮。读取页面不签发链接、不结算。 */
Page({
  data: { state: 'loading', stateKind: 'loading', stateTitle: '加载中', stateCta: '', promo: null },

  onLoad() { this.load(); },
  onPullDownRefresh() { this.load().then(() => wx.stopPullDownRefresh()); },

  load() {
    const epoch = this._epoch = (this._epoch || 0) + 1;
    if (this.data.state !== 'ready') this.setData({ state: 'loading', stateKind: 'loading', stateTitle: '加载中', stateCta: '' });
    return api.affiliate(app).then(res => {
      if (epoch !== this._epoch) return;
      if (!api.ok(res) || !res.data) {
        const auth = res && (res.httpStatus === 401 || Number(res.code) === 401);
        this.setData({ state: auth ? 'auth' : 'error', stateKind: auth ? 'no-permission' : 'data',
          stateTitle: auth ? '登录后查看' : '收益暂未加载', stateCta: auth ? '登录' : '重新加载' });
        return;
      }
      this.setData({ state: 'ready', promo: view.affiliateView(res.data) });
    });
  },

  onStateCta() {
    if (this.data.state === 'auth') { app.requireLogin({ reason: '登录后查看收益' }).then(ok => { if (ok) this.load(); }); return; }
    this.load();
  }
});
