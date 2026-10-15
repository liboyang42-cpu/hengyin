const { runtime, safeNumber, stateName } = require('../lib/runtime');
const modal = require('../../utils/modal');
// 页面只加展示用字段;接口、请求号与原操作重试沿用归档实现
const METRIC = { DISTINCT_ROUTES: ['不同路线数', '条路线'], EXPLORATION_DAYS: ['探索自然日数', '个探索日'] };
const BADGE = { ACTIVE: 'tu-badge--wait', SCHEDULED: 'tu-badge--plain', ACHIEVED: 'tu-badge--ok', ENDED: 'tu-badge--plain', CANCELLED: 'tu-badge--plain' };
function decorate(x) {
  const m = METRIC[x.metric] || [x.metric || '目标', ''];
  const known = typeof x.progress === 'number';
  return { ...x, stateText: stateName(x.state), metricText: m[0], unit: m[1], badge: BADGE[x.state] || 'tu-badge--plain',
    progressText: known ? x.progress + ' / ' : '', pct: known && x.target ? Math.min(100, Math.round(x.progress / x.target * 100)) : -1 };
}
function sh(ms) { return new Date(ms + 8 * 3600000); }
function ymd(d) { return d.getUTCFullYear() + '-' + String(d.getUTCMonth() + 1).padStart(2, '0') + '-' + String(d.getUTCDate()).padStart(2, '0'); }
function periods() {
  const today = sh(Date.now()); const day = 86400000; const t0 = Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), today.getUTCDate());
  const monday = t0 - ((today.getUTCDay() + 6) % 7) * day;
  const first = Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), 1), nextFirst = Date.UTC(today.getUTCFullYear(), today.getUTCMonth() + 1, 1);
  // 服务端只收今天及以后的开始日:本周/本月从今天起算,结束日不变
  return [['本周', Math.max(monday, t0), monday + 7 * day], ['本月', Math.max(first, t0), nextFirst], ['30 天', t0, t0 + 30 * day]].map(([label, a, b]) => {
    const start = ymd(new Date(a)), end = ymd(new Date(b));
    return { label, start, end, short: start.slice(5).replace('-', '/') + '–' + end.slice(5).replace('-', '/') };
  });
}
async function refresh() { const v = await this.client.list(null, 20); this._next = v.nextBeforeId; this.setData({ rows: v.items.map(decorate), hasMore: v.hasMore }); }
Page(runtime('goals', { data: { target: '5', startDate: '', endDate: '', metricIndex: 0, metrics: ['不同路线数', '探索自然日数'], creating: false, periods: [], periodIndex: 1, today: '' }, start() { this.usePeriod(1); this.run(() => refresh.call(this)); }, refresh, retry() { return this.client.retry(); }, methods: {
  metricChange(e) { if (!this.data.pending && !this.data.busy) this.setData({ metricIndex: Number(e.detail.value) }); },
  pickMetric(e) { if (!this.data.pending && !this.data.busy) this.setData({ metricIndex: Number(e.currentTarget.dataset.value) }); },
  stepTarget(e) { if (this.data.pending || this.data.busy) return; const next = Math.max(1, Math.min(100, (Number(this.data.target) || 1) + Number(e.currentTarget.dataset.delta))); this.setData({ target: String(next) }); },
  usePeriod(index) { const list = periods(); const p = list[index]; this.setData({ periods: list, periodIndex: index, startDate: p.start, endDate: p.end, today: list[2].start }); },
  pickPeriod(e) { if (!this.data.pending && !this.data.busy) this.usePeriod(Number(e.currentTarget.dataset.index)); },
  toggleCreate() { if (this.data.busy) return; this.usePeriod(this.data.periodIndex); this.setData({ creating: !this.data.creating, error: '' }); },
  loadList() { this.run(() => refresh.call(this)); },
  dismissError() { this.setData({ error: '' }); },
  nextPage() { this.run(() => this.client.list(this._next, 20), v => { this._next = v.nextBeforeId; this.setData({ rows: this.data.rows.concat(v.items.map(decorate)), hasMore: v.hasMore }); }); },
  create() { this.run(() => this.client.create({ requestId: this.fresh(), metric: this.data.metricIndex === 0 ? 'DISTINCT_ROUTES' : 'EXPLORATION_DAYS', target: safeNumber(this.data.target), startDate: this.data.startDate, endDateExclusive: this.data.endDate }), r => { this.setData({ detail: decorate(r), creating: false }); return refresh.call(this); }); },
  openRecord(e) { this.run(() => this.client.detail(e.currentTarget.dataset.id), d => this.setData({ detail: decorate(d) })); },
  closeDetail() { this.setData({ detail: null }); },
  // 取消不可恢复(服务端单向置位),弹窗写清后果;按钮用「取消目标 / 保留」,避免两个「取消」
  cancelGoal() {
    if (this.data.busy || !this.client || !this.data.detail) return;
    const generation = this._generation, who = this._identity, id = this.data.detail.id;
    modal.show({ title: '取消这个目标？', content: '取消后不再统计进度，也不能恢复。此操作不可撤销。', confirmText: '取消目标', cancelText: '保留', danger: true,
      success: res => { if (res.confirm && this.current(generation, who)) this.run(() => this.client.cancel(id), d => { this.setData({ detail: decorate(d) }); return refresh.call(this); }); } });
  }
} }));
