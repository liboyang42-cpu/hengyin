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
    // 2026-10-28:当前地图中心。本地地址里没有的,用它向 /api/map/place-search 就近搜(业主:搜「海星市场」要能搜到)
    lat: { type: Number, value: 0 },
    lng: { type: Number, value: 0 },
  },
  data: { q: '', open: false, results: [], searching: false, remoteError: false },
  lifetimes: { detached() { if (this._searchTimer) clearTimeout(this._searchTimer); this._searchToken = (this._searchToken || 0) + 1; } },
  observers: {
    // 地址列表换了(换城市 / 重新拉附近)要跟着重算,否则列表里还留着上一批
    places() { if (this.data.open) this._refresh(this.data.q); },
  },
  methods: {
    _refresh(q) {
      const local = matchPlaces(this.data.places, q);
      this.setData({ results: decoratePlaces(local).slice(0, MAX_RESULTS) });
      this._searchRemote(q, local);
    },
    /* 输入停 350ms 再搜,只认最后一次;结果接在本地地址后面,同名去重 */
    _searchRemote(q, local) {
      if (this._searchTimer) clearTimeout(this._searchTimer);
      const keyword = String(q || '').trim();
      const token = this._searchToken = (this._searchToken || 0) + 1;
      const lat = Number(this.data.lat), lng = Number(this.data.lng);
      if (keyword.length < 2 || !lat || !lng || typeof getApp !== 'function' || typeof getApp().sendRequest !== 'function') { if (this.data.searching || this.data.remoteError) this.setData({ searching: false, remoteError: false }); return; }
      this.setData({ searching: true, remoteError: false });
      this._searchTimer = setTimeout(() => {
        getApp().sendRequest({
          url: '/api/map/place-search', method: 'POST', data: { keyword, longitude: lng, latitude: lat }, hideLoading: true, silentError: true,
          success: (res) => {
            if (token !== this._searchToken) return;
            const rows = res && (res.code === 200 || res.code === '200') && Array.isArray(res.data) ? res.data : [];
            const names = {}; (local || []).forEach((p) => { names[p && p.name] = true; });
            const far = rows.filter((r) => r && r.name && !names[r.name]).map((r, i) => ({ id: 'place-' + i, kind: 'place', name: r.name, address: r.address || '', lat: r.lat, lng: r.lng }));
            this.setData({ searching: false, results: decoratePlaces((local || []).concat(far)).slice(0, MAX_RESULTS) });
          },
          // 联网搜不到时如实说一句,本地地址照常可选
          fail: () => { if (token === this._searchToken) this.setData({ searching: false, remoteError: true }); },
          successStatusAbnormal: () => { if (token === this._searchToken) this.setData({ searching: false, remoteError: true }); },
        });
      }, 350);
    },
    /* 原型是两个状态:点白条进搜索态,点「取消」或压暗层退回。
       不是「聚焦就展开」—— 那样退不回去,压暗层也没地方挂。 */
    onOpen() { this.setData({ open: true }); this._refresh(this.data.q); },
    onCancel() { this._searchToken = (this._searchToken || 0) + 1; this.setData({ open: false, results: [], searching: false, remoteError: false }); },
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
      this._searchToken = (this._searchToken || 0) + 1;
      this.setData({ q: row.name || '', open: false, results: [], searching: false });
      this.triggerEvent('pick', row);
    },
  },
});
