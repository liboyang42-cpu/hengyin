// §21 固定玩法预设 → 个人玩法草稿。结构照抄队伍详情页(标题 + 卡片 + 行),三态:列表 / 填写 / 已建草稿。
// 数据:GET /api/preset/gameplay/list;写:POST /api/preset/gameplay/draft(JSON,带 requestId)。
// 错误原文写在按钮上方红字,不弹窗;结果未知按同一 requestId 重查,不另建一份。
const presets = require('../utils/publish/gameplay-presets.js');
const app = getApp();

Page({
  data: {
    statusBarHeight: app.globalData.statusBarHeight,
    navBarHeight: app.globalData.navBarHeight,
    stage: 'list',
    loading: true,
    loadError: '',
    rows: [],
    preset: null,
    form: [],
    todos: [],
    submitting: false,
    submitError: '',
    unknown: false,
    draftId: ''
  },

  onLoad(options) {
    // 预设草稿是登录本人的个人模板(/draft 没有商家作用域),所以本页只有玩家深色一种
    this._values = {};
    this.load();
  },
  onUnload() { this._seq = (this._seq || 0) + 1; },

  load() {
    const seq = this._seq = (this._seq || 0) + 1;
    this.setData({ loading: true, loadError: '' });
    app.sendRequest({
      url: '/api/preset/gameplay/list', method: 'GET', hideLoading: true, autoErrorToast: false,
      success: res => {
        if (seq !== this._seq) return;
        if (!res || String(res.code) !== '200') { this.setData({ loading: false, loadError: app.getRequestErrorMessage(res, '预设没有读到') }); return; }
        this._catalog = presets.list(res.data);
        this.setData({ loading: false, rows: this._catalog.map(r => ({ presetKey: r.presetKey, title: r.title, purpose: r.purpose })) });
      },
      fail: res => { if (seq === this._seq) this.setData({ loading: false, loadError: app.getRequestErrorMessage(res, '网络没连上') }); }
    });
  },

  openPreset(e) {
    const row = (this._catalog || []).find(r => r.presetKey === e.currentTarget.dataset.key);
    if (!row) return;
    this._preset = row.raw;
    this._values = {};
    this._missing = [];
    this._requestId = presets.newRequestId();
    this.setData({ stage: 'form', preset: { title: row.title, purpose: row.purpose }, todos: (row.raw.authorTodos || []).map(String),
      form: presets.formRows(row.raw, this._values), submitError: '', unknown: false });
    wx.pageScrollTo && wx.pageScrollTo({ scrollTop: 0, duration: 0 });
  },

  backToList() {
    if (this.data.submitting) return;
    this.setData({ stage: 'list', preset: null, form: [], submitError: '', unknown: false });
  },

  _refresh() { this.setData({ form: presets.formRows(this._preset, this._values, this._missing) }); },
  _touch() {
    if (this._missing && this._missing.length) this._missing = presets.missing(this._preset, this._values);
    if (!this.data.unknown && this.data.submitError) this.setData({ submitError: '' });
  },

  onText(e) {
    const key = e.currentTarget.dataset.key;
    const was = (this._missing || []).includes(key);
    this._values[key] = e.detail.value; this._touch();
    if (was && !this._missing.includes(key)) this._refresh();
  },
  onTextDone() { this._refresh(); },
  onChoice(e) { const d = e.currentTarget.dataset; this._values[d.key] = d.id; this._touch(); this._refresh(); },
  onBin(e) {
    const d = e.currentTarget.dataset;
    const map = Object.assign({}, this._values[d.key] || {});
    map[d.item] = d.bin;
    this._values[d.key] = map; this._touch(); this._refresh();
  },
  onMove(e) {
    const d = e.currentTarget.dataset;
    const row = this.data.form.find(r => r.key === d.key);
    if (!row) return;
    this._values[d.key] = presets.moveItem(row.items.map(i => i.id), Number(d.index), Number(d.delta));
    if (wx.vibrateShort) wx.vibrateShort({ type: 'light' });
    this._touch(); this._refresh();
  },

  submit() {
    if (this.data.submitting || !this._preset) return;
    if (!this.data.unknown) {
      this._missing = presets.missing(this._preset, this._values);
      if (this._missing.length) { this._refresh(); this.setData({ submitError: '还有 ' + this._missing.length + ' 项没填' }); return; }
    }
    // 结果未知后:同一 requestId、同一份输入重查,服务端只回原草稿
    const body = this.data.unknown && this._lastBody ? this._lastBody : presets.draftBody(this._preset, this._values, this._requestId);
    this._lastBody = body;
    this.setData({ submitting: true, submitError: '' });
    app.sendRequest({
      url: '/api/preset/gameplay/draft', method: 'POST', hideLoading: true, autoErrorToast: false,
      header: { 'Content-Type': 'application/json' }, data: JSON.stringify(body),
      success: res => {
        const r = presets.draftResult(res);
        if (r.state === 'CREATED') {
          this.setData({ submitting: false, unknown: false, stage: 'done', draftId: r.draftId, todos: r.todos.length ? r.todos : this.data.todos });
          return;
        }
        if (r.state === 'UNKNOWN') { this.setData({ submitting: false, unknown: true, submitError: '建草稿结果未确认' }); return; }
        // 服务端拒绝:原文;换了输入就换新号(同号异内容会冲突)
        this._requestId = presets.newRequestId();
        this._lastBody = null;
        this.setData({ submitting: false, unknown: false, submitError: r.message });
      },
      fail: () => this.setData({ submitting: false, unknown: true, submitError: '建草稿结果未确认' })
    });
  },

  openDraft() {
    if (!this.data.draftId) return;
    wx.redirectTo({ url: '/pages/publish/temp/index?id=' + this.data.draftId });
  }
});
