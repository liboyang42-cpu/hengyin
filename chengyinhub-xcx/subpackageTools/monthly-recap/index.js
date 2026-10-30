const { page } = require('../cloud-ten-runtime');
const { createMonthlyRecapClient } = require('../utils/features/monthly-recap-client');
const { toTimestamp } = require('../../utils/datetime');
const occurredAt = item => toTimestamp(item.occurredAtMs == null ? item.occurredAt : item.occurredAtMs);
const COVERAGE = '仅权威完成路线会话、已结算漫游地点与本人有效城市签；不从报名或客户端进度推测完成';
function shanghai(ms) { return new Date(Number(ms) + 8 * 3600000); }
function thisMonth() { const d = shanghai(Date.now()); return d.getUTCFullYear() + '-' + String(d.getUTCMonth() + 1).padStart(2, '0'); }
function shift(month, delta) { const [y, m] = month.split('-').map(Number); const d = new Date(Date.UTC(y, m - 1 + delta, 1)); return d.getUTCFullYear() + '-' + String(d.getUTCMonth() + 1).padStart(2, '0'); }
// 周柱只在当月记录已全部读完时出现(hasMore=false),由真实记录按上海日期分到第 1–5 周
function weeksOf(items, hasMore) {
  if (hasMore || !items.length) return [];
  const counts = [0, 0, 0, 0, 0];
  items.forEach(item => { const ms = occurredAt(item); if (Number.isFinite(Number(ms))) counts[Math.min(4, Math.floor((shanghai(ms).getUTCDate() - 1) / 7))]++; });
  const max = Math.max(...counts);
  return counts.map((count, i) => ({ label: '第' + (i + 1) + '周', count, height: max ? Math.round(count / max * 220) : 0, isMax: max > 0 && count === max }));
}
function recapPatch(value) {
  const items = value.items.map(item => { const ms = occurredAt(item); const d = shanghai(ms); return Object.assign({}, item, { dateText: Number.isFinite(Number(ms)) ? (d.getUTCMonth() + 1) + '/' + d.getUTCDate() : '' }); });
  const weeks = weeksOf(items, value.hasMore);
  return { items, hasMore: value.hasMore, counts: value.counts, coverage: COVERAGE, weeks, weekPicked: -1, weeksLabel: weeks.map(w => w.label + ' ' + w.count + ' 次').join('，') };
}
Page(page({
  data: {"kindIndex": 0, "kinds": ["路线", "首次地点", "城市签"], "counts": null, "coverage": "", "monthLabel": "", "isCurrentMonth": true, "weeks": [], "weekPicked": -1, "weeksLabel": ""},
  client: createMonthlyRecapClient,
  show(query) { const month = /^\d{4}-\d{2}$/.test(query.month || '') && query.month <= thisMonth() ? query.month : thisMonth(); this.setMonth(month); },
  methods: {
    chooseMonth(event) { this._month = event.detail.value.slice(0, 7); this.refresh(); },
    chooseKind(event) { const index = Number(event.detail.value); this.setData({ kindIndex: index }); this.refresh(); },
    refresh() { return this.run(() => this.client.load(this._month, ['routes', 'places', 'stamps'][this.data.kindIndex]), recapPatch); },
    next() { return this.run(() => this.client.next(), recapPatch); },
    setMonth(month) { const [y, m] = month.split('-'); this._month = month; this.setData({ monthLabel: y + ' 年 ' + Number(m) + ' 月', isCurrentMonth: month >= thisMonth() }); this.refresh(); },
    prevMonth() { if (!this.data.busy) this.setMonth(shift(this._month, -1)); },
    nextMonth() { if (!this.data.busy && !this.data.isCurrentMonth) this.setMonth(shift(this._month, 1)); },
    pickKind(event) { const index = Number(event.currentTarget.dataset.index); if (this.data.busy || index === this.data.kindIndex) return; this.setData({ kindIndex: index }); this.refresh(); },
    pickWeek(event) { this.setData({ weekPicked: Number(event.currentTarget.dataset.index) }); }
  }
}));
