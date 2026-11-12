// D-82(2026-11-11 业主「章节结束卡奖励行:加章节级奖励(需后端加字段)」):
// 编辑器章节设置填一行奖励文案 → 随主题保存上送 rewardText → /api/play/nodes 的 chapters[].rewardText
// → 章节卡 reward → 章节结束卡多一行「奖励 · …」;没设就整行不出。
'use strict';
const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.join(__dirname, '..', '..');
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8');
const { buildGateFlip } = require('../../pages/play/utils/chapter-flip-data.js');

global.getApp = global.getApp || (() => ({ globalData: {}, tips() {} }));
global.wx = global.wx || { showToast() {}, vibrateShort() {}, nextTick(cb) { cb(); } };

function loadPage(file) {
  let def = null;
  const prev = global.Page;
  global.Page = (d) => { def = d; };
  const abs = path.join(ROOT, file);
  delete require.cache[require.resolve(abs)];
  require(abs);
  global.Page = prev;
  return def;
}

function instance(def) {
  const inst = Object.assign({}, def, { data: JSON.parse(JSON.stringify(def.data)) });
  inst.setData = function (patch) {
    Object.entries(patch).forEach(([key, value]) => {
      const parts = key.split('.'); let target = this.data;
      parts.slice(0, -1).forEach((part) => { target = target[part]; });
      target[parts.at(-1)] = value;
    });
  };
  return inst;
}

// ---------- 编辑器 ----------

const EDITOR_INPUT = /<view class="chapter-name-field" wx:if="\{\{!chapterForm\.opening && !chapterForm\.ending\}\}">\s*<text class="chapter-name-field__label">章节奖励<\/text>\s*<input class="chapter-name-field__input" type="text" maxlength="(\d+)"[^>]*value="\{\{chapterForm\.rewardText\}\}" bindinput="onChapterRewardInput"/;

test('编辑器:章节设置复用章节名的「标签 + 填充输入框」,序章/结局章不出,长度上限与后端同一个数', () => {
  const wxml = read('pages/publish/fabu/index.wxml');
  const m = EDITOR_INPUT.exec(wxml);
  assert.ok(m, '章节奖励输入框缺失或没复用 chapter-name-field');
  const java = read('../chengyinhub-system/src/main/java/com/chengyinhub/business/util/ChapterRewardText.java');
  assert.equal(m[1], /MAX_CODE_POINTS = (\d+);/.exec(java)[1], '前端 maxlength 与 ChapterRewardText.MAX_CODE_POINTS 不一致');
  // 0 新 wxss:复用的类在页面样式里本来就有
  const wxss = read('pages/publish/fabu/index.wxss');
  for (const cls of ['.chapter-name-field', '.chapter-name-field__label', '.chapter-name-field__input', '.chapter-name-field__ph']) {
    assert.ok(wxss.includes(cls + ' {') || wxss.includes(cls + '{'), cls);
  }
});

test('负控:输入框绑错字段 / 去掉序章结局门 都判红', () => {
  const wxml = read('pages/publish/fabu/index.wxml');
  assert.equal(EDITOR_INPUT.test(wxml.replace('value="{{chapterForm.rewardText}}" bindinput="onChapterRewardInput"', 'value="{{chapterForm.name}}" bindinput="onChapterRewardInput"')), false);
  assert.equal(EDITOR_INPUT.test(wxml.replace('wx:if="{{!chapterForm.opening && !chapterForm.ending}}">\n          <text class="chapter-name-field__label">章节奖励', '>\n          <text class="chapter-name-field__label">章节奖励')), false);
});

test('编辑器:新章表单默认空奖励,输入写进 chapterForm,上送 payload 原样带 rewardText', () => {
  const def = loadPage('pages/publish/fabu/index.js');
  const p = instance(def);
  assert.equal(p.data.chapterForm.rewardText, '', '新章默认没有奖励');
  p.onChapterRewardInput({ detail: { value: '隐藏线索 ×1' } });
  assert.equal(p.data.chapterForm.rewardText, '隐藏线索 ×1');
  // 自由探索 / 非故事流的上送:整章深拷贝,rewardText 不被白名单丢掉
  const payload = p._nonStoryPayloadChapter({ name: '第一章', rewardText: '隐藏线索 ×1', nodes: [], blocks: [] });
  assert.equal(payload.rewardText, '隐藏线索 ×1');
  assert.equal(payload.blocks, undefined);
});

// ---------- 玩家端 ----------

test('玩家端:/api/play/nodes 的 chapters[].rewardText → 章节卡 reward;没下发 = 空串', () => {
  const def = loadPage('pages/play/index.js');
  const page = Object.assign({}, def, { data: {} });
  const cards = page.buildChapterCards({ chapters: [
    { chapterId: 11, name: '邮局旧址', rewardText: '隐藏线索 ×1' },
    { chapterId: 12, name: '老书店' },
  ] }, {});
  assert.equal(cards[0].reward, '隐藏线索 ×1');
  assert.equal(cards[1].reward, '');
});

test('玩家端:上一章设了奖励,完成屏带 reward;没设就是空串(不出那一行)', () => {
  const nodes = [{ nodeId: 11, chapterId: 1, done: true }, { nodeId: 21, chapterId: 2, done: false, name: '老书店' }];
  const withReward = buildGateFlip({ cards: [{ id: 1, title: '邮局旧址', reward: '隐藏线索 ×1' }, { id: 2, title: '老书店' }], chapterId: 2, nodes });
  assert.equal(withReward.endInfo.reward, '隐藏线索 ×1');
  const none = buildGateFlip({ cards: [{ id: 1, title: '邮局旧址' }, { id: 2, title: '老书店' }], chapterId: 2, nodes });
  assert.equal(none.endInfo.reward, '');
  // 奖励不占三格数据(数据格会被当数字滚动,文字放进去会坏)
  assert.ok(!withReward.endInfo.rows.some((r) => r.k === '奖励'));
});

function loadFlip() {
  let def = null;
  const prev = global.Component;
  global.Component = (d) => { def = d; };
  const file = path.join(ROOT, 'pages/play/components/chapter-flip/index.js');
  delete require.cache[require.resolve(file)];
  require(file);
  global.Component = prev;
  return def;
}

function flip(def, endInfo) {
  const inst = {
    data: Object.assign({}, def.data, { mode: 'gate', faces: [], endInfo, info: { number: '02', title: '老书店' }, reducedMotion: true }),
    setData(patch) { Object.assign(this.data, patch); },
    triggerEvent() {},
  };
  Object.keys(def.methods).forEach((k) => { inst[k] = def.methods[k].bind(inst); });
  inst._at = () => {}; inst._assemble = () => {}; inst._decode = () => {}; inst._ctx = null; inst._S = 1; inst._timers = [];
  return inst;
}

test('章节结束卡:endCard 带上 reward;wxml 只在有奖励时出一行,复用既有类、不加新样式', () => {
  const def = loadFlip();
  const end = { number: '01', kicker: '第 1 章完成', title: '邮局旧址', rows: [{ k: '坐标', v: '1/1' }], reward: '隐藏线索 ×1' };
  const c = flip(def, end);
  c._gate();
  assert.equal(c.data.endCard.reward, '隐藏线索 ×1');
  const bare = flip(def, Object.assign({}, end, { reward: undefined }));
  bare._gate();
  assert.equal(bare.data.endCard.reward, '');
  [c, bare].forEach((i) => i._timers.forEach(clearTimeout));   // 完成屏的停留计时,测试里不等它

  const wxml = read('pages/play/components/chapter-flip/index.wxml');
  assert.match(wxml, /<view class="cf__stats2 cf__d" wx:if="\{\{endCard\.reward\}\}">奖励 · \{\{endCard\.reward\}\}<\/view>/);
  const wxss = read('pages/play/components/chapter-flip/index.wxss');
  assert.ok(/\.cf__stats2 \{/.test(wxss) && /\.cf__d \{/.test(wxss), '复用的类必须已存在');
  assert.doesNotMatch(wxss, /reward/, '分包余量只剩几 KiB:奖励行不新增样式规则');
});
