// 顶部输入地址(§6.1)。四个模式共用这一件:白框是唯一入口,抽屉里不再有同名的一行。
// 组件只负责「输入 → 过滤 → 选中」,选中之后去哪由页面决定(bind:pick)。
const { matchPlaces, decoratePlaces } = require('../../../utils/map-address-search.js');
// 2026-10-27 owner P-33:下拉面板最多 5 条结果,面板之下要还能看到地图
const MAX_RESULTS = 5;

Component({
  properties: {
    // 可搜的地址。每条至少要有 id / name / kind;kind ∈ hangout|city|free|none。
    places: { type: Array, value: [] },
    // 顶边像素值。由页面按 chrome.contentTop 算好传进来 —— 组件拿不到胶囊坐标。
    top: { type: Number, value: 96 },
    placeholder: { type: String, value: '输入地址' },
    entry: { type: Boolean, value: false },
  },
  data: { q: '', open: false, results: [] },
  observers: {
    // 地址列表换了(换城市 / 重新拉附近)要跟着重算,否则列表里还留着上一批
    places() { if (this.data.open) this._refresh(this.data.q); },
  },
  methods: {
    _refresh(q) {
      this.setData({ results: decoratePlaces(matchPlaces(this.data.places, q)).slice(0, MAX_RESULTS) });
    },
    /* 原型是两个状态:点白条进搜索态,点「取消」或压暗层退回。
       不是「聚焦就展开」—— 那样退不回去,压暗层也没地方挂。 */
    onOpen() { this.setData({ open: true }); this._refresh(this.data.q); },
    onCancel() { this.setData({ open: false, results: [] }); },
    onInput(e) {
      const q = (e.detail && e.detail.value) || '';
      this.setData({ q, open: true });
      this._refresh(q);
    },
    onConfirm() {
      // 键盘上的「搜索」= 选中第一条,和点它一样
      const first = this.data.results[0];
      if (first) this._pick(first);
    },

    onPick(e) {
      const row = this.data.results[Number(e.currentTarget.dataset.i)];
      if (row) this._pick(row);
    },
    // 2026-10-27 owner P-32:每条结果可直接导航(微信内置地图),与迷雾无关
    onNavigate(e) {
      const row = this.data.results[Number(e.currentTarget.dataset.i)];
      const latitude = Number(row && row.lat); const longitude = Number(row && row.lng);
      if (!row || !Number.isFinite(latitude) || !Number.isFinite(longitude) || typeof wx === 'undefined' || !wx.openLocation) return;
      wx.openLocation({ latitude, longitude, name: row.name || '', address: row.address || '', scale: 17 });
    },
    _pick(row) {
      // 选完收起列表,但把选中的名字留在框里 —— 清空会让人以为白点了一次
      this.setData({ q: row.name || '', open: false, results: [] });
      this.triggerEvent('pick', row);
    },
  },
});
