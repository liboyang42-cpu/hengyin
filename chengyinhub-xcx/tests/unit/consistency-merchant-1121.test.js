'use strict';
// 2026-11-21 商家端一致性走查:按钮统一 cy-btn、置灰按钮点了要说明原因、空态统一 cy-empty、
// 删掉与标题/按钮重复的说明小字、热区 ≥ 88rpx、触碰过的 wxss 不留裸色值。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '..', '..');
const read = (rel) => fs.readFileSync(path.join(root, rel), 'utf8');

// 去掉注释后再比对:注释里允许保留历史数值说明
const stripCss = (src) => src.replace(/\/\*[\s\S]*?\*\//g, '');
const stripWxml = (src) => src.replace(/<!--[\s\S]*?-->/g, '');

function loadPage(rel, extraGlobals) {
  const file = path.join(root, rel);
  const saved = {};
  const globals = Object.assign({
    getApp: () => ({ globalData: { navBarHeight: 44 }, sendRequest() {}, getUserID: () => '' }),
    getCurrentPages: () => [],
    wx: { showToast() {}, getStorageSync() { return ''; }, setStorageSync() {} },
  }, extraGlobals || {});
  let definition;
  globals.Page = (page) => { definition = page; };
  for (const key of Object.keys(globals)) { saved[key] = global[key]; global[key] = globals[key]; }
  delete require.cache[require.resolve(file)];
  try { require(file); } finally {
    for (const key of Object.keys(globals)) {
      if (saved[key] === undefined) delete global[key]; else global[key] = saved[key];
    }
    delete require.cache[require.resolve(file)];
  }
  return definition;
}

function ctx(definition, data) {
  return {
    data: Object.assign({}, definition.data, data),
    setData(update) { Object.assign(this.data, update); },
  };
}

test('触碰过的商家 wxss 不留裸 #hex / rgba 色值(ds-ok 标注行除外)', () => {
  for (const rel of [
    'pages/merchant/index/index.wxss',
    'pages/merchant/apply/index.wxss',
    'pages/merchant/predict/index.wxss',
    'pages/merchant/marketing/index.wxss',
    'pages/merchant/crm-campaigns/index.wxss',
    'pages/merchant/customer/index.wxss',
    'pages/merchant/team/index.wxss',
  ]) {
    const lines = stripCss(read(rel).split('\n').filter((l) => !/ds-ok/.test(l)).join('\n')).split('\n');
    const bad = lines.filter((l) => /#[0-9a-fA-F]{3,8}\b|rgba?\(/.test(l));
    assert.deepEqual(bad, [], rel + ' 仍有裸色值');
  }
  const home = read('pages/merchant/index/index.wxss');
  assert.match(home, /\.rv-biz \{[^}]*background: var\(--cy-color-status-success-soft\)/);
  assert.match(home, /\.rv-biz--off \{[^}]*background: var\(--cy-color-status-neutral-soft\)/);
});

test('工作台:营业 pill / 清空 / 都看完了 热区外扩到 88rpx;选章/选节点面板不再重复标题', () => {
  const wxss = read('pages/merchant/index/index.wxss');
  assert.match(wxss, /\.rv-biz::after \{[^}]*top: -22rpx; bottom: -22rpx;/);
  assert.match(wxss, /\.rv-msg-clear::after \{[^}]*top: -4rpx; bottom: -4rpx;/);
  assert.match(wxss, /\.rv-msg-done::after \{[^}]*top: -8rpx; bottom: -8rpx;/);
  const wxml = read('pages/merchant/index/index.wxml');
  assert.doesNotMatch(wxml, /chpick__hint|选择本次核销的章节|选择本次核销的节点/);
});

test('预测待答:「就是这个」用 cy-btn disabled,点灰按钮提示先选答案', () => {
  const wxml = stripWxml(read('pages/merchant/predict/index.wxml'));
  assert.doesNotMatch(wxml, /class="pdm-btn pdm-btn--/);
  assert.match(wxml, /<cy-btn class="pdm-btn" variant="secondary" bindtap="onCancelPick">先不给<\/cy-btn>/);
  assert.match(wxml, /<cy-btn class="pdm-btn" variant="primary" disabled="\{\{!pickedKey\}\}"[^>]*bindtap="onAskConfirm" bind:disabledtap="onPickRequired">就是这个<\/cy-btn>/);
  assert.match(read('pages/merchant/predict/index.json'), /"cy-btn": "\/components\/cy\/btn\/index"/);
  assert.match(wxml, /pageState === 'login'\}\}" kind="no-permission" fill size="lg" title="登录后查看预测" cta="登录"/);

  const toasts = [];
  const def = loadPage('pages/merchant/predict/index.js');
  const savedPages = global.getCurrentPages;
  global.getCurrentPages = () => [{ selectComponent: () => ({ show: (o) => toasts.push(o.title) }) }];
  try { def.onPickRequired.call(ctx(def, {})); } finally {
    if (savedPages === undefined) delete global.getCurrentPages; else global.getCurrentPages = savedPages;
  }
  assert.deepEqual(toasts, ['先选出正确答案']);
});

test('常备权益:保存置灰时点按钮,在字段下方标出缺的那几栏(与 save 同口径)', () => {
  const wxml = read('pages/merchant/decor/perks/index.wxml');
  assert.match(wxml, /disabled="\{\{!canSave \|\| saveReadbackPending\}\}"\s+bindtap="save"\s+bind:disabledtap="onSaveBlocked"/);
  const def = loadPage('pages/merchant/decor/perks/index.js');
  const c = ctx(def, { form: Object.assign({}, def.data.form, { name: '', retailValue: '', quota: '' }) });
  def.onSaveBlocked.call(c);
  assert.equal(c.data.fieldErrors.name, '请填写权益名称');
  assert.ok(c.data.fieldErrors.retailValue);
  assert.equal(c.data.fieldErrors.quota, '请填写正整数可接待份数');
  const busy = ctx(def, { saving: true, fieldErrors: {} });
  def.onSaveBlocked.call(busy);
  assert.deepEqual(busy.data.fieldErrors, {}, '保存中点灰按钮不重复报错');
});

test('置灰按钮都接了 disabledtap:评价提交 / 相册保存 / AI 形象保存 / 声音生成', () => {
  assert.match(read('pages/merchant/reviews/index.wxml'), /disabled="\{\{!canSubmit \|\| submitting\}\}" bindtap="submitReview" bind:disabledtap="onSubmitBlocked"/);
  assert.match(read('pages/merchant/reviews/index.js'), /onSubmitBlocked\(\) \{[\s\S]*?buildCreateDraft\(this\.data\)[\s\S]*?submitError: draft\.error/);
  assert.match(read('pages/merchant/decor/gallery/index.wxml'), /bindtap="save" bind:disabledtap="onSaveBlocked"/);
  assert.match(read('pages/merchant/decor/gallery/index.js'), /onSaveBlocked\(\) \{[\s\S]*?toast\('相册还没有改动'\)/);
  const npc = read('pages/merchant/decor/ai-npc/index.wxml');
  assert.match(npc, /disabled="\{\{processing \|\| \(!photoReady && source === 'photo'\)\}\}"\s+bind:disabledtap="onSaveBlocked"/);
  assert.match(npc, /disabled="\{\{voiceUnknown \|\| !voiceConsent\}\}" bind:disabledtap="onUploadVoiceBlocked"/);
  const npcJs = read('pages/merchant/decor/ai-npc/index.js');
  assert.match(npcJs, /onSaveBlocked\(\) \{[\s\S]*?toast\('先选择一张照片'\)/);
  assert.match(npcJs, /onUploadVoiceBlocked\(\) \{[\s\S]*?voiceError: '请确认这是本人声音或已获得声音使用授权'/);
});

test('页内自绘按钮改为 cy-btn:店铺参谋主题弹层 / 客户批量与导出条 / 套票行内动作', () => {
  const insight = read('pages/merchant/marketing/ai-insight/index.wxml');
  assert.match(insight, /<cy-btn class="ts-btn ts-btn--ghost" variant="secondary" bindtap="closeTopic">先不用<\/cy-btn>/);
  assert.match(insight, /<cy-btn class="ts-btn" variant="primary" bindtap="applyTopic" data-topic-id="\{\{sheetTopic\.topicId\}\}"/);
  assert.match(read('pages/merchant/marketing/ai-insight/index.json'), /"cy-btn"/);
  assert.match(read('pages/merchant/marketing/ai-insight/index.wxss'), /\.seg-btn::after \{[^}]*top: -14rpx; bottom: -14rpx;/);
  assert.doesNotMatch(insight, /基础数据暂未完整返回|sub="数据暂缺"/);

  const customer = stripWxml(read('pages/merchant/customer/index.wxml'));
  assert.doesNotMatch(customer, /<button[^>]*cu-inline-button/);
  assert.equal((customer.match(/<cy-btn[^>]*class="cu-inline-button"[^>]*size="sm"/g) || []).length, 4);
  assert.match(read('pages/merchant/bundle-reservations/index.wxml'), /<cy-btn wx:if="\{\{item\.canNoShow\}\}" variant="secondary" size="sm"/);
  assert.match(read('pages/merchant/team/index.wxml'), /class="tm-share-btn" open-type="share" hover-class="cy-pressed" hover-stay-time="80"/);
});

test('重复/内部说明小字已删,空态与登录态统一', () => {
  for (const rel of ['pages/merchant/decor/index.wxml', 'pages/merchant/decor/coop-setting/index.wxml', 'pages/merchant/decor/gallery/index.wxml']) {
    assert.doesNotMatch(read(rel), /完成商家入驻后可用/, rel);
  }
  assert.doesNotMatch(read('pages/merchant/crm-campaigns/index.wxml'), /名单不含联系方式|频控跳过/);
  assert.doesNotMatch(read('pages/merchant/ledger/batch-detail/index.wxml'), /明细在履约成立后逐条计入/);
  assert.doesNotMatch(read('pages/merchant/citynode/index.wxml'), /申请上漫游地图/);
  assert.doesNotMatch(read('pages/merchant/aftercare/index.wxml'), /换个关键词试试/);
  assert.doesNotMatch(read('pages/merchant/marketing/index.wxml'), /正在确认商家身份/);
  const coop = read('pages/merchant/coop-center/index.wxml');
  assert.match(coop, /!routes\.length\}\}" fill size="lg" kind="empty"/);
  assert.match(coop, /!events\.length\}\}" fill size="lg" kind="empty"/);
  for (const rel of ['pages/merchant/decor/gallery/index.wxml', 'pages/merchant/citynode/create/index.wxml']) {
    assert.match(read(rel), /'login'\}\}" kind="no-permission" fill size="lg" title="登录后[^"]+" cta="登录"/, rel);
  }
});

test('商家页所有自绘可点元素都带 hover-stay-time="80"', () => {
  const files = [];
  (function walk(dir) {
    for (const name of fs.readdirSync(path.join(root, dir))) {
      const rel = path.join(dir, name);
      if (fs.statSync(path.join(root, rel)).isDirectory()) walk(rel);
      else if (rel.endsWith('.wxml')) files.push(rel);
    }
  })('pages/merchant');
  const missing = [];
  const tagRe = /<([a-zA-Z][\w-]*)\b((?:[^>"']|"[^"]*"|'[^']*')*)>/g;
  for (const rel of files) {
    const src = read(rel);
    let m;
    while ((m = tagRe.exec(src))) {
      const tag = m[1];
      const attrs = m[2];
      if (tag.startsWith('cy-')) continue;
      if (/\b(bind|catch):?tap=/.test(attrs) && !/hover-stay-time/.test(attrs)) missing.push(rel + ':' + src.slice(0, m.index).split('\n').length);
    }
  }
  assert.deepEqual(missing, []);
});
