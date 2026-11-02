'use strict';
// C-12(2026-11-02 walk-fix 第三轮):队长结束本场后,未完成的站不再弹「开始任务」半屏盖住
// 顶部「本场已结束 · 你完成了 N/M 站」;已完成的站仍能回看。真 Page 对象上跑 openSheet。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
let spec; const toasts = [];
global.getApp = () => ({ getUserID: () => '9', globalData: { features: {} }, isDevEnv: () => false, sendRequest() {} });
global.Behavior = x => x;
global.wx = { getStorageSync: () => undefined, setStorageSync() {}, removeStorageSync() {},
  getSystemInfoSync: () => ({ statusBarHeight: 20 }), showToast: o => toasts.push(o.title) };
global.Page = x => { spec = x; };
require('../../pages/play/index.js');
function page(lead) {
  return { ...spec, _routeInteractionBlocked: () => false, _lockedTip: () => false,
    data: { ...structuredClone(spec.data), lead, allDone: false },
    setData(patch) { for (const [k, v] of Object.entries(patch)) {
      const parts = k.split('.'); let t = this.data;
      for (const p of parts.slice(0, -1)) t = t[p]; t[parts.at(-1)] = v; } } };
}
const node = done => ({ nodeId: '29', name: '外滩源壹号', address: '中山东一路33号', done, hasTemplate: true });

test('本场已结束:未完成站不弹开始任务半屏', () => {
  const p = page({ exists: true, ended: true });
  p.openSheet(node(false));
  assert.equal(p.data.sheet.show, false);
});
test('本场已结束:从章节故事流进来时收起故事流,露出顶部结束条', () => {
  const p = page({ exists: true, ended: true });
  let closed = 0;
  p.data.chapterFull = true;
  p.closeChapterFull = () => { closed++; p.data.chapterFull = false; };
  p.openSheet(node(false));
  assert.equal(closed, 1);
  assert.equal(p.data.sheet.show, false);
});
test('本场已结束:已完成站仍可回看', () => {
  const p = page({ exists: true, ended: true });
  p.openSheet(node(true));
  assert.equal(p.data.sheet.show, true);
});
test('本场进行中:未完成站照常打开', () => {
  const p = page({ exists: true, ended: false, status: 2 });
  p.openSheet(node(false));
  assert.equal(p.data.sheet.show, true);
});
test('站点卡 half 档起步,不再固定撑满 full 档', () => {
  const wxss = fs.readFileSync(path.join(__dirname, '../../pages/play/index.wxss'), 'utf8');
  const block = wxss.match(/\.sheet\{([^}]+)\}/)[1];
  assert.match(block, /min-height:min\(var\(--cy-comp-sheet-half\)/);
  assert.doesNotMatch(block, /(?:^|[;\s])height:min\(var\(--cy-comp-sheet-full\)/);
});
