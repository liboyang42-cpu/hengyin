// 俱乐部合作卡(2026-10-28 m689,Figma 350:557):合作池里开放承接的主题,最多 3 张。
// 组件自己读、自己判身份 —— 店铺参谋页的请求顺序与计数有契约锁着,不往页面里加请求。
// 读不到 / 没有开放主题就整段不出(与「推荐主题」同口径,不连坐页面主卡)。
const app = getApp();
const COOP_CENTER_ROUTE = '/pages/merchant/coop-center/index';

function memberKey() {
  return String(typeof app.getUserID === 'function' ? (app.getUserID() || '') : '');
}
function deadlineText(v) {
  const m = /^(\d{4})-(\d{2})-(\d{2})(?:[ T](\d{2}):(\d{2}))?/.exec(String(v || ''));
  return m ? Number(m[2]) + '月' + Number(m[3]) + '日' + (m[4] ? ' ' + m[4] + ':' + m[5] : '') : '';
}

Component({
  data: { cards: [], poolError: false },
  lifetimes: {
    attached() { this._detached = false; this.load(); },
    detached() { this._detached = true; this._epoch = (this._epoch || 0) + 1; },
  },
  pageLifetimes: {
    show() { if (this._member !== memberKey()) this.load(); },
  },
  methods: {
    load() {
      const member = this._member = memberKey();
      const epoch = this._epoch = (this._epoch || 0) + 1;
      if (!member) { this.setData({ cards: [] }); return; }
      app.sendRequest({
        hideLoading: true, silentError: true, url: '/api/coop/pool/list', method: 'POST',
        data: JSON.stringify({}), header: { 'content-type': 'application/json' },
        success: (res) => {
          if (this._detached || epoch !== this._epoch || member !== memberKey()) return;
          const rows = res && String(res.code) === '200' && res.data && Array.isArray(res.data.rows) ? res.data.rows : [];
          this.setData({ poolError: false, cards: rows.filter((r) => r && r.state === 'open' && r.topicId != null).slice(0, 3).map((r) => ({
            topicId: String(r.topicId), name: r.name || '', cover: r.cover || '', host: r.merchantNick || '', deadline: deadlineText(r.recruitDeadline),
          })) });
        },
        // 读不到时在原位说一句并给重试,不让整段悄悄消失
        fail: () => { if (!this._detached && epoch === this._epoch) this.setData({ cards: [], poolError: true }); },
      });
    },
    openAll() { wx.navigateTo({ url: COOP_CENTER_ROUTE }); },
    openCard(e) {
      const id = String((e.currentTarget.dataset || {}).topicId || '');
      wx.navigateTo({ url: COOP_CENTER_ROUTE + (/^\d+$/.test(id) ? '?topicId=' + id : '') });
    },
  },
});
