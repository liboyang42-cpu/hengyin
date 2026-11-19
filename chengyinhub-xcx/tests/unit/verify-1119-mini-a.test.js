// 1119 复核修复(mini-a 道):PG-* 逐项的源码合同。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '..', '..');
const read = (rel) => fs.readFileSync(path.join(root, rel), 'utf8');
const exists = (rel) => fs.existsSync(path.join(root, rel));
const noComments = (s) => s.replace(/<!--[\s\S]*?-->/g, '');

test('PG-001:游客的发现帖文流只读社区帖,俱乐部帖文一路要登录,两路都没有时落登录入口', () => {
  const js = read('pages/talent/list/index.js');
  assert.match(js, /if \(app\.getUserID\(\) > 0\) sources\.push\(\{ fetch: function \(beforeId\)/);
  assert.match(js, /if \(!sources\.length\) return Promise\.reject\(\{ loginRequired: true \}\);/);
  assert.match(js, /feedLoginRequired: !!\(res && res\.loginRequired\)/);
  assert.match(js, /retryFeed\(\) \{\n\s*if \(!this\.data\.feedLoginRequired\) return this\.loadFeed\(\);/);
});

test('PG-002/PG-004:我的队伍重试不把点击事件当回调;登录门只包「我的」页签', () => {
  const wxml = noComments(read('subpackageRoam/my-teams/index.wxml'));
  const js = read('subpackageRoam/my-teams/index.js');
  assert.doesNotMatch(wxml, /bindtap="reload"/);
  assert.match(wxml, /bindtap="retryReload"/);
  assert.match(js, /retryReload\(\) \{ this\.reload\(\); \}/);
  assert.doesNotMatch(js, /if \(done\) done\(\)/);
  assert.doesNotMatch(wxml, /<cy-access-gate/);
  assert.match(wxml, /wx:if="\{\{tab === 'mine' && guest\}\}"/);
  assert.match(js, /if \(!app\.ensureLoginFor\('team'/);
});
