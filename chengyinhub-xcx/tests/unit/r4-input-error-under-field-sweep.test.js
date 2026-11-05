'use strict';
// 第四轮业主标准(2026-11-03):用户输入的问题用 cy-inline-error(kind="error",红)写在字段下方,
// 不用蓝/灰提示框,也不弹窗。本测试钉住 l3-sell 扫查改掉的位置(清单见 l3-sell/RESULT.md Round 4 Sweep)。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ROOT = path.resolve(__dirname, '../..');
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8');

function tags(file) {
  return read(file).match(/<cy-inline-error\b[^>]*>/g) || [];
}

// 这些文件里所有字段校验类的 cy-inline-error 都必须是红色;下面列出的是允许保留其他档的(加载/提交/网络)。
const FILES = {
  'pages/publish/activity/index.wxml': /ownerLoadError|submitError/,
  'pages/topic/merchantapply/index.wxml': /submitError|loadError|load-failure/,
  'pages/publish/temp/index.wxml': /submitError|load-failure/,
  'pages/publish/fabu/runtime-structure-editor.wxml': null,
  'pages/publish/fabu/survival-editor.wxml': null,
  'pages/publish/fabu/runtime-editor.wxml': null,
  'pages/publish/fabu/topic-detail-sheet.wxml': null,
  'pages/publish/components/topic-state-keys/index.wxml': /deleteError|load-failure/,
  'pages/topic/components/cy/date-range-sheet/index.wxml': null,
};

// 第十二轮 k3(j1 N9/F11、p2 N-10、业主规则「输入错误是栏下红字」):栏下红字是 kind="field"(无底色无图标);
// kind="error" 仍是红,但带粉底与图标,只留给非字段的操作失败。两者都不许回到蓝色 data 档。
test('字段校验提示一律红色(kind="field" 栏下红字或 kind="error"),不再用默认蓝色 data 档', () => {
  for (const [file, allowed] of Object.entries(FILES)) {
    for (const t of tags(file)) {
      if (/load-failure/.test(t) || (allowed && allowed.test(t))) continue;
      assert.match(t, /kind="(error|field)"/, file + ' 里的输入问题必须是红色: ' + t.slice(0, 120));
    }
  }
  assert.match(read('pages/activity/official-organizer/index.wxml'), /formError.action === 'resume'\}\}" kind="error"/);
  assert.match(read('pages/coop/invite-detail/index.wxml'), /wx:if="\{\{draftError\}\}" kind="field"/);
  assert.match(read('pages/merchant/customer/detail/index.wxml'), /wx:if="\{\{noteError\}\}"\s+kind="error"/);
});

test('已在字段下方报错的表单不再另弹提示', () => {
  assert.doesNotMatch(read('pages/publish/activity/index.js'), /cyToast\('请完善表单信息'\)/);
  assert.doesNotMatch(read('pages/topic/merchantapply/index.js'), /toast\('请(先选择承接地点|选择可配合日期区间|填写可用空间与现场条件|添加场地照片)'\)/);
  assert.doesNotMatch(read('pages/coop/invite-detail/index.js'), /toast\('先写(拒绝|撤回)理由'\)/);
});

function loadPage(rel, extraWx) {
  let config;
  const toasts = [];
  global.getApp = () => ({ globalData: { statusBarHeight: 20, navBarHeight: 44, userInfo: {} }, sendRequest() {}, getRequestErrorMessage: (_r, f) => f });
  global.Page = (c) => { config = c; };
  global.getCurrentPages = () => [{}];
  global.wx = Object.assign({
    showToast(o) { toasts.push(o && o.title); }, getStorageSync: () => '', setStorageSync() {}, removeStorageSync() {},
    getSystemInfoSync: () => ({}), getWindowInfo: () => ({}),
  }, extraWx || {});
  const abs = path.join(ROOT, rel);
  delete require.cache[require.resolve(abs)];
  require(abs);
  const page = Object.assign({}, config);
  page.data = JSON.parse(JSON.stringify(config.data));
  page.setData = function (patch, cb) {
    for (const k of Object.keys(patch)) {
      const parts = k.split('.');
      let o = this.data;
      while (parts.length > 1) { const p = parts.shift(); o[p] = o[p] || {}; o = o[p]; }
      o[parts[0]] = patch[k];
    }
    if (cb) cb();
  };
  return { page, toasts };
}

test('参与人信息:姓名/手机号的问题各自写在该栏下方', () => {
  const { page } = loadPage('pages/addressinfo/addressinfo.js');
  assert.equal(page.validateForm({ fullName: '', mobilePhone: '123' }), false);
  assert.deepEqual(page.data.fieldErrors, { fullName: '请输入姓名', mobilePhone: '请输入正确的手机号' });
  page.onInputChange({ currentTarget: { dataset: { field: 'fullName' } }, detail: { value: '许' } });
  assert.equal(page.data.fieldErrors.fullName, '');
  const wxml = read('pages/addressinfo/addressinfo.wxml');
  assert.match(wxml, /wx:if="\{\{fieldErrors.fullName\}\}" kind="error"/);
  assert.match(wxml, /wx:if="\{\{fieldErrors.mobilePhone\}\}" kind="error"/);
});

test('群发通知:标题/内容的问题写在各自字段下方', () => {
  const { page } = loadPage('pages/club/notify/index.js');
  page.data.title = '';
  page.data.content = 'x';
  assert.equal(page.validDraft(), false);
  assert.equal(page.data.titleError, '标题需为 1–80 字');
  assert.equal(page.data.contentError, '');
  const wxml = read('pages/club/notify/index.wxml');
  assert.match(wxml, /wx:if="\{\{titleError\}\}" kind="error"/);
  assert.match(wxml, /wx:if="\{\{contentError\}\}" kind="error"/);
});

test('常备权益:每栏的问题写在该栏下方', () => {
  const wxml = read('pages/merchant/decor/perks/index.wxml');
  for (const k of ['name', 'retailValue', 'unitCost', 'quota', 'validEnd']) {
    assert.match(wxml, new RegExp('wx:if="\\{\\{fieldErrors.' + k + '\\}\\}" kind="error"'));
  }
});
