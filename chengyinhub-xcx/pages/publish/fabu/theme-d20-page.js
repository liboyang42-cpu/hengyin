'use strict';
// 文档二 L1(2026-10-28):主题详情里「d20 检定 / 可玩时段 / 开局前准备」的页面数据与处理器,
// 照 theme-ownership.js 的样子展开进 fabu Page,不在 index.js 里再长一段。
// 都写在 formData.journeyRules 上(字段见 theme-d20.js);页面上的几项都是它的派生值,
// 打开主题详情和每次改动后由 _syncD20Rows 重算。
const themeD20 = require('./theme-d20.js');
const storyInitialState = require('../utils/publish/story-initial-state.js');
const cyToast = require('../../../utils/toast.js');

const SAVE_FAILED = '没有保存成功，请再试一次。';

const data = {
  d20Enabled: false,
  d20Summary: '',
  d20Sheet: { show: false },
  prepSheet: { show: false },
  prepItems: [],
  playWindowSheet: { show: false, error: '' },
  playWindow: { mode: 'any', start: '18:00', end: '22:00' },
  playWindowLabel: '不限',
  playWindowOptions: themeD20.WINDOWS,
  playWindowStarts: themeD20.WINDOW_STARTS,
  playWindowEnds: themeD20.WINDOW_ENDS,
  playWindowStartIndex: 4,
  playWindowEndIndex: 2,
};

function failure(error) {
  return (error && error.message) || SAVE_FAILED;
}

const methods = {
  _syncD20Rows(raw) {
    const rules = raw == null ? this.data.formData.journeyRules : raw;
    const model = themeD20.read(rules);
    const w = themeD20.playWindowOf(rules);
    this.setData({
      d20Enabled: model.checkEnabled,
      d20Summary: themeD20.summary(rules),
      prepItems: themeD20.prepOf(rules),
      playWindow: w,
      playWindowLabel: themeD20.playWindowLabel(w),
      playWindowStartIndex: Math.max(0, themeD20.WINDOW_STARTS.indexOf(w.start)),
      playWindowEndIndex: Math.max(0, themeD20.WINDOW_ENDS.indexOf(w.end)),
    });
  },
  // 走同一个 serialize:校验口径与服务端 JourneyRulesValidator 一致,不另写一套
  _writeD20Rules(rules) {
    const raw = storyInitialState.serializeChanges(this.data.formData.journeyRules, rules);
    this.setData({ 'formData.journeyRules': raw });
    this._syncD20Rows(raw);
    return raw;
  },
  onD20Toggle(e) {
    try {
      this._writeD20Rules(themeD20.setCheckEnabled(this.data.formData.journeyRules, !!e.detail.value));
    } catch (error) {
      this.setData({ d20Enabled: !e.detail.value });
      cyToast(failure(error));
    }
  },
  openD20Sheet() { this.setData({ 'd20Sheet.show': true }); },
  closeD20Sheet() { this.setData({ 'd20Sheet.show': false }); },
  onD20Save(e) {
    try {
      this._writeD20Rules(e.detail.rules);
      this.setData({ 'd20Sheet.show': false });
    } catch (error) { cyToast(failure(error)); }
  },
  openPrepSheet() { this.setData({ 'prepSheet.show': true }); },
  closePrepSheet() { this.setData({ 'prepSheet.show': false }); },
  onPrepChange(e) {
    try {
      this._writeD20Rules(themeD20.setPrep(this.data.formData.journeyRules, e.detail.items));
    } catch (error) { cyToast(failure(error)); }
  },
  openPlayWindow() { this.setData({ 'playWindowSheet.show': true, 'playWindowSheet.error': '' }); },
  closePlayWindow() { this.setData({ 'playWindowSheet.show': false }); },
  _writePlayWindow(w) {
    try {
      this._writeD20Rules(themeD20.setPlayWindow(this.data.formData.journeyRules, w));
      this.setData({ 'playWindowSheet.error': '' });
    } catch (_) { this.setData({ 'playWindowSheet.error': SAVE_FAILED }); }
  },
  onPlayWindowPick(e) {
    this._writePlayWindow(Object.assign({}, this.data.playWindow, { mode: e.detail.value }));
  },
  onPlayWindowTime(e) {
    const field = e.currentTarget.dataset.field;
    const list = field === 'start' ? themeD20.WINDOW_STARTS : themeD20.WINDOW_ENDS;
    const value = list[Number(e.detail.value)];
    if (!value) return;
    const next = Object.assign({}, this.data.playWindow);
    if (field === 'start') next.start = value; else next.end = value;
    this._writePlayWindow(next);
  },
};

/** 发布确认「本次发布」里和票务同一种摘要行;d20 关着时没有这一行。 */
function publishSummaryLine(raw) {
  const model = themeD20.read(raw);
  return model.checkEnabled ? `d20 检定 · ${model.attrs.length} 项属性` : '';
}

module.exports = { data, methods, publishSummaryLine };
