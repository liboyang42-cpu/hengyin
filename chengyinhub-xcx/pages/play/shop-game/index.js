// W7 x-shop-game-runner:店铺小游戏一局(据点页「玩今天的小游戏」,或城市大富翁落在店铺格上)。
// 发局 POST /api/shop-games/launch → cy-advanced-game(shop-play-id)开这局自己的会话并玩 → POST /api/shop-games/result → 看结果。
// 服务端判定每一步;不在店里也能玩,只记成绩,不发积分。所有 409 都给一句人话,不留死路。
const runner = require('../utils/shop-game-runner.js');
const cyToast = require('../../../utils/toast.js');

const app = getApp();

function locate() {
  return new Promise((resolve) => {
    wx.getLocation({
      type: 'gcj02',
      success: (loc) => resolve({ lat: loc.latitude, lng: loc.longitude, accuracy: loc.accuracy }),
      fail: () => resolve(null),
    });
  });
}

function send(url, data) {
  return new Promise((resolve) => app.sendRequest({
    url, method: 'POST', write: true, hideLoading: true, silentError: true,
    data: JSON.stringify(data || {}), header: { 'Content-Type': 'application/json' },
    success: resolve, successStatusAbnormal: resolve, fail: () => resolve(null),
  }));
}

Page({
  data: { step: 'loading', reasonText: '', playId: 0, node: null, over: false, finishing: false, finishNote: '', verdict: null },

  onLoad(options) {
    this._poiId = Number((options || {}).poiId) || 0;
    this._source = (options || {}).source === 'CITY_LANDING' ? 'CITY_LANDING' : 'PLACE_PAGE';
    this.begin();
  },
  onUnload() { this._gone = true; },

  retry() { this.begin(); },
  exit() { wx.navigateBack({ fail: () => wx.reLaunch({ url: '/pages/roam/index' }) }); },

  begin() {
    if (app.ensureLoginFor && !app.ensureLoginFor('roam', () => this.begin(), { owner: this, target: () => [this._poiId] })) return;
    if (!this._poiId) { this.setData({ step: 'unavailable', reasonText: runner.reasonText('NOT_A_SHOP') }); return; }
    this.setData({ step: 'loading', finishNote: '', over: false });
    locate().then((fix) => {
      if (!fix) return { code: 409, errorCode: 'LOCATION_REQUIRED' };
      return send(runner.LAUNCH_PATH, runner.launchBody(this._poiId, this._source, fix));
    }).then((res) => {
      if (this._gone) return;
      if (!res) { this.setData({ step: 'unavailable', reasonText: runner.reasonText('UNAVAILABLE') }); return; }
      if (Number(res.code) !== 200) { this.setData({ step: 'unavailable', reasonText: runner.reasonText(runner.reasonOf(res)) }); return; }
      const view = runner.launchView(res.data);
      if (view.kind === 'unavailable') { this.setData({ step: 'unavailable', reasonText: view.text }); return; }
      this.setData({ playId: view.playId });
      if (view.kind === 'played') { this.record(null); return; }
      this.setData({ step: 'play', node: { nodeId: view.playId, name: view.gameName } });
    });
  },

  onSession(e) {
    const status = e && e.detail && e.detail.status;
    this.setData({ over: runner.sessionOver(status) });
  },

  finish() {
    if (this.data.finishing) return;
    this.setData({ finishing: true, finishNote: '' });
    locate().then((fix) => this.record(fix));
  },

  record(fix) {
    send(runner.RESULT_PATH, runner.finishBody(this.data.playId, fix)).then((res) => {
      if (this._gone) return;
      this.setData({ finishing: false });
      if (res && Number(res.code) === 200) { this.setData({ step: 'verdict', verdict: runner.verdictView(res.data) }); return; }
      const text = runner.reasonText(runner.reasonOf(res));
      if (this.data.step === 'play') { this.setData({ finishNote: text }); cyToast('还没有结果'); return; }
      this.setData({ step: 'unavailable', reasonText: text });
    });
  },
});
