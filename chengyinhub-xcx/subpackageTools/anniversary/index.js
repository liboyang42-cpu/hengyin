const { runtime, safeNumber } = require('../lib/runtime');
// 展示到分钟:接口回的是「yyyy-MM-dd HH:mm:ss.SSS」
function withText(items) { return (items || []).map(x => Object.assign({}, x, { completedText: String(x.completedAt || '').replace('T', ' ').slice(0, 16) })); }
function today() { const d = new Date(Date.now() + 8 * 3600000); return { month: d.getUTCMonth() + 1, day: d.getUTCDate(), year: d.getUTCFullYear() }; }
// 首次进入读今天(month/day 留空 = 今天,归档接口约定);换一天只换月日
Page(runtime('anniversary', { data: { month: '', day: '', dateLabel: '', pickerValue: '' }, start() { const t = today(); this.setData({ dateLabel: t.month + '.' + String(t.day).padStart(2, '0'), pickerValue: t.year + '-' + String(t.month).padStart(2, '0') + '-' + String(t.day).padStart(2, '0') }); this.loadMemories(); }, methods: {
  pickDate(e) { if (this.data.busy) return; const [, m, d] = String(e.detail.value).split('-'); this.setData({ month: String(Number(m)), day: String(Number(d)), dateLabel: Number(m) + '.' + d, pickerValue: e.detail.value }); this.loadMemories(); },
  loadMemories() { this.setData({ rows: [], hasMore: false }); this.run(() => this.client.load(this.data.month || this.data.day ? safeNumber(this.data.month) : null, this.data.month || this.data.day ? safeNumber(this.data.day) : null, 20), v => this.setData({ rows: withText(v.items), hasMore: v.hasMore })); },
  nextPage() { this.run(() => this.client.next(), v => this.setData({ rows: this.data.rows.concat(withText(v.items)), hasMore: v.hasMore })); }
} }));
