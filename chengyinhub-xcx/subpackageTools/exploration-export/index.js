const { runtime } = require('../lib/runtime');
const toast = require('../../utils/toast.js');
const { transfer } = require('../lib/files');
function ymd(ms) { const d = new Date(ms + 8 * 3600000); return d.getUTCFullYear() + '-' + String(d.getUTCMonth() + 1).padStart(2, '0') + '-' + String(d.getUTCDate()).padStart(2, '0'); }
function ranges() { const now = Date.now(), day = 86400000, end = ymd(now + day), y = ymd(now).slice(0, 4);
  // 服务端单次导出上限 366 天:第三档是「近一年」而不是「全部」
  return [{ label: '近 30 天', start: ymd(now - 29 * day), end }, { label: '今年', start: y + '-01-01', end }, { label: '近一年', start: ymd(now - 365 * day), end }]; }
Page(runtime('export', { data: { startDate: '', endDate: '', prepared: false, completed: false, count: 0, expected: 0, ranges: [], rangeIndex: 0, pct: 0, fileName: '' }, start() { this.useRange(0); }, methods: {
  useRange(index) { const list = ranges(); this.setData({ ranges: list, rangeIndex: index, startDate: list[index].start, endDate: list[index].end }); },
  pickRange(e) { if (!this.data.busy && !this.data.prepared) this.useRange(Number(e.currentTarget.dataset.index)); },
  restart() { if (this.data.busy) return; this.deleteFiles(); this._exportParts = []; this._file = null; this.setData({ prepared: false, completed: false, fileReady: false, count: 0, pct: 0, error: '' }); },
  prepare() { this.deleteFiles(); this._exportParts = []; this._file = null; this.setData({ fileReady: false, completed: false, prepared: false, count: 0 }); this.run(() => this.client.prepare({ startDate: this.data.startDate, endDateExclusive: this.data.endDate }), d => { this._exportSnapshot = d.snapshot; this.setData({ prepared: true, expected: d.snapshot.expectedRows }); }); },
  readNext() { this.run(() => this.client.next(), chunk => {
    if (chunk.restartRequired || !chunk.data) { this._exportParts = []; this._file = null; this.setData({ prepared: false, completed: false, fileReady: false, error: '来源已变化，已有部分不完整，请重新准备' }); return; }
    this._exportParts.push(chunk.data); const checkpoint = chunk.checkpoint;
    this.setData({ count: checkpoint.offset, pct: this.data.expected ? Math.min(100, Math.round(checkpoint.offset / this.data.expected * 100)) : 100, completed: chunk.complete });
    if (chunk.complete) { this._file = { filename: 'chengyin-exploration-' + this._exportSnapshot.startDate.replace(/-/g, '') + '-' + this._exportSnapshot.endDateExclusive.replace(/-/g, '') + '.jsonl', content: this._exportParts.join(''), expiresAt: this._exportSnapshot.expiresAtMs }; this.setData({ fileReady: true, fileName: this._file.filename }); }
  }); },
  saveFile() { this.sendFile(false); }, shareFile() { this.sendFile(true); },
  sendFile(share) { this.run(() => { if (!this._file || !this.data.completed) throw new Error('导出未完成，请继续读取所有分块'); return transfer(this, this._file, share); }, message => { if (message) toast(message); }); }
} }));
