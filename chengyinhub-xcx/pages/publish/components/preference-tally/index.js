// x-life-games:偏好题(validation_method=6)给模板主人看的结果汇总,只读。
// 数据来自 GET /api/template/preference-tally(x-template-games R1):只有人数与占比,不带玩家;
// 回答不满 minResponses(3)份时服务端只给总数,这里也只显示总数。
const app = getApp();
const { view } = require('../../utils/preference-tally-view.js');

Component({
  options: { styleIsolation: 'apply-shared' },
  properties: {
    templateId: { type: Number, value: 0 },
    scope: { type: String, value: '' }
  },
  data: { loading: false, error: '', total: 0, min: 3, enough: false, rows: [] },
  observers: {
    'templateId, scope': function () { this.load(); }
  },
  lifetimes: { detached() { this._token = (this._token || 0) + 1; } },
  methods: {
    load() {
      const id = Number(this.data.templateId) || 0;
      const token = (this._token || 0) + 1;
      this._token = token;
      if (id <= 0) { this.setData({ loading: false, error: '', total: 0, enough: false, rows: [] }); return; }
      this.setData({ loading: true, error: '' });
      app.sendRequest({
        hideLoading: true,
        silentError: true,
        url: '/api/template/preference-tally',
        method: 'GET',
        data: { templateId: id, scope: this.data.scope || '' },
        success: (res) => {
          if (token !== this._token) return;
          if (res && String(res.code) === '200') {
            const v = view(res.data);
            this.setData({ loading: false, error: '', total: v.total, min: v.min, enough: v.enough, rows: v.rows });
          }
          else this.setData({ loading: false, error: (res && res.msg) || '汇总暂时读不到，请稍后再试' });
        },
        fail: () => {
          if (token !== this._token) return;
          this.setData({ loading: false, error: '汇总暂时读不到，请稍后再试' });
        }
      });
    },
    retry() { this.load(); }
  }
});

