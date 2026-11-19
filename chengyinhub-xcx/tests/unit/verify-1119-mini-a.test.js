// 1119 复核修复(mini-a 道):PG-* 逐项的源码合同。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '..', '..');
const read = (rel) => fs.readFileSync(path.join(root, rel), 'utf8');
const exists = (rel) => fs.existsSync(path.join(root, rel));
const noComments = (s) => s.replace(/<!--[\s\S]*?-->/g, '');

test('PG-001:游客读俱乐部帖文被拒时只显示社区帖,两路都没有时落登录入口', () => {
  const js = read('pages/talent/list/index.js');
  assert.match(js, /if \(communityOn\) resolve\(\{ rows: \[\], hasMore: false, cursor: beforeId \}\);\n\s*else reject\(\{ loginRequired: true \}\);/);
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

test('PG-003:URL 参数解码走 utils/safe-decode,坏 % 序列回退原值', () => {
  const { safeDecode } = require('../../utils/safe-decode.js');
  assert.equal(safeDecode('%E5%9F%8E'), '城');
  assert.equal(safeDecode('%E5%9F'), '%E5%9F');
  assert.equal(safeDecode(undefined), '');
  for (const f of ['subpackageRoam/citystamp/index.js', 'subpackageB/pages/im/chat/index.js', 'pages/play/poi-challenge/index.js',
    'subpackageMember/utils/public-catalog.js', 'subpackageMember/coupon-qr/index.js', 'subpackageMember/market/gift/index.js']) {
    assert.doesNotMatch(read(f), /decodeURIComponent\(/, f);
    assert.match(read(f), /require\('[./]+utils\/safe-decode\.js'\)/, f);
  }
  assert.doesNotMatch(read('pages/publish/fabu/index.js'), /decodeURIComponent\(options\.templateName\)/);
});

test('PG-005/PG-091:据点核销码页先判登录,并改为 cy-scene-qr-citynode 薄壳', () => {
  const wxml = noComments(read('subpackageRoam/citynode-code/index.wxml'));
  const js = read('subpackageRoam/citynode-code/index.js');
  const json = JSON.parse(read('subpackageRoam/citynode-code/index.json'));
  assert.match(wxml, /<cy-access-gate kind="login" \/>/);
  assert.match(wxml, /<cy-scene-qr-citynode poi-id="\{\{poiId\}\}" name="\{\{name\}\}" bind:close="onClose" \/>/);
  assert.doesNotMatch(wxml, /<cy-qr-voucher/);
  assert.doesNotMatch(js, /sendRequest|setInterval/);
  assert.equal(json.usingComponents['cy-scene-qr-citynode'], '/components/cy/scene-qr-citynode/index');
  assert.equal(json.usingComponents['cy-access-gate'], '/components/cy/access-gate/index');
});

test('PG-006:漫游地图据点评价带并复用 request_id,成功后清掉', () => {
  const js = read('subpackageRoam/nearby/index.js');
  assert.match(js, /reply_id: 0, request_id: this\._reviewReq\.id \}/);
  assert.match(js, /if \(!this\._reviewReq \|\| this\._reviewReq\.poiId !== String\(c\.id\)\)/);
  assert.match(js, /this\._reviewReq = null;\n\s*this\.setData\(\{ 'poiCard\.myText': ''/);
});
