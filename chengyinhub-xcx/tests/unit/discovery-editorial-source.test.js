const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const read = name => fs.readFileSync(path.join(__dirname, '../..', name), 'utf8');
test('首页精选明确来源，不凭开始时间承诺参加资格', () => {
  const js = read('pages/index/index.js');
  const wxml = read('pages/index/index.wxml');
  assert.match(js, /is_recommend: 1/);
  assert.match(wxml, /编辑精选 · 参加条件以详情为准/);
  assert.doesNotMatch(js.slice(js.indexOf('_buildRecoHero() {'), js.indexOf('onHeroBannerTap(e) {')), /进行中|fmtRemain/);
});

test('兴趣排除未有合同时只展示不可用和未知，不伪造保存或生效', () => {
  const wxml = read('pages/mylike/mylike.wxml');
  assert.match(wxml, /当前状态：暂时无法查询兴趣排除记录或确认生效状态/);
  assert.match(wxml, /disabled="\{\{true\}\}">排除兴趣影响/);
  assert.match(wxml, /disabled="\{\{true\}\}">恢复兴趣影响/);
  assert.match(wxml, /取消收藏不等于排除推荐影响/);
  assert.doesNotMatch(wxml, /已生效|已保存偏好/);
});

test('精选说明打开现有偏好页，不编个人兴趣理由', () => {
  const js = read('pages/index/index.js');
  const start = js.indexOf('  showRecommendationSource() {');
  const end = js.indexOf('\n  },', start);
  let dialog;
  const routes = [];
  const fn = new Function('modal', 'wx', 'return function() {' + js.slice(js.indexOf('{', start) + 1, end) + '}')({ show: value => { dialog = value; } }, { navigateTo: value => routes.push(value.url) });
  fn();
  assert.match(dialog.content, /编辑精选，不是根据个人兴趣生成/);
  dialog.success({ confirm: false });
  assert.deepEqual(routes, []);
  dialog.success({ confirm: true });
  assert.deepEqual(routes, ['/pages/mylike/mylike']);
});
