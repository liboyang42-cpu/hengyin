// U02-03 / U02-04 地点待办:编辑器里一行入口 + 一张 full 档弹层。
// 读 /binding-status(作者或商家岗位),绑定只走 /binding(服务端校验归属、编辑窗口、同城、修订号)。
// 只有 level=error 才写「挡住发布」;warn 只提示。没有地点合同的主题整块不渲染。
const todos = require('../../utils/publish/location-todos.js');
const app = getApp();

Component({
  options: { addGlobalClass: true },
  properties: {
    topicId: { type: String, value: '' },
    scope: { type: String, value: '' },
    chapters: { type: Array, value: [] }
  },
  data: { view: null, summary: '', open: false, pickShow: false, pickItems: [], rowError: {}, busyKey: '' },
  observers: {
    topicId(id) { if (id) this.load(); }
  },
  lifetimes: { detached() { this._seq = (this._seq || 0) + 1; } },
  pageLifetimes: { show() { if (this.data.topicId && this._loaded) this.load(); } },
  methods: {
    load() {
      const seq = this._seq = (this._seq || 0) + 1;
      const topicId = this.data.topicId;
      app.sendRequest({
        url: '/api/template/topic-template/binding-status', method: 'POST', hideLoading: true, autoErrorToast: false,
        data: { topicId, scope: this.data.scope || '' },
        success: res => {
          if (seq !== this._seq || topicId !== this.data.topicId) return;
          this._loaded = true;
          // 读取失败不画「没有待办」:保留上次确认的视图
          if (!res || String(res.code) !== '200') return;
          const view = todos.view(res.data);
          this.setData({ view, summary: todos.summaryText(view) });
        }
      });
    },
    openSheet() { if (this.data.view) this.setData({ open: true }); },
    closeSheet() { if (!this.data.busyKey) this.setData({ open: false, pickShow: false }); },
    pickNode(e) {
      if (this.data.busyKey) return;
      const nodes = todos.bindableNodes(this.data.chapters);
      const slotKey = e.currentTarget.dataset.slot;
      if (!nodes.length) { this.setData({ ['rowError.' + slotKey]: '先在章节里给节点设置这座城市的地点并保存' }); return; }
      this._pickSlot = slotKey;
      this._pickNodes = nodes;
      this.setData({ pickShow: true, pickItems: nodes.map(n => n.label) });
    },
    closePick() { this.setData({ pickShow: false }); },
    onPicked(e) {
      const node = this._pickNodes && this._pickNodes[e.detail.index];
      const slotKey = this._pickSlot;
      this.setData({ pickShow: false });
      if (!node || !slotKey) return;
      const row = (this.data.view.rows || []).find(r => r.slotKey === slotKey);
      const data = { topicId: this.data.topicId, scope: this.data.scope || '', slotKey, nodeId: node.id };
      if (row && row.revision != null) data.expectedRevision = String(row.revision);
      this.setData({ busyKey: slotKey, ['rowError.' + slotKey]: '' });
      app.sendRequest({
        url: '/api/template/topic-template/binding', method: 'POST', hideLoading: true, autoErrorToast: false, data,
        success: res => {
          this.setData({ busyKey: '' });
          if (res && String(res.code) === '200') { this.load(); return; }
          // 服务端原文写在该行下面(城市不一致、已被他人更新、编辑窗口已关…)
          this.setData({ ['rowError.' + slotKey]: res && res.msg || '绑定失败' });
          if (res && String(res.code) === '409') this.load();
        },
        fail: () => {
          // 结果未知:先重读权威状态,不自动重发
          this.setData({ busyKey: '', ['rowError.' + slotKey]: '绑定结果未确认，已重新读取' });
          this.load();
        }
      });
    }
  }
});
