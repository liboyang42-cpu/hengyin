const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

/* 第十一轮 h4 第 8 项(f2 走查 LOW)。源码级钉住:旧写法回来就红。 */
const read = (rel) => fs.readFileSync(path.join(__dirname, '../..', rel), 'utf8');

test('「主题审核中」不再承诺「审核通过后就能开一场」(商业主题过审后还要招商、定价、开售)', () => {
  const js = read('pages/club/detail/index.js');
  assert.doesNotMatch(js, /审核通过后就能开一场/);
  assert.match(js, /review: \{ label: '主题审核中', hint: '' \}/);
});

test('用户看得到的「帖子」统一叫「帖文」', () => {
  const files = ['pages/roam/index.js', 'pages/shezhi/components/post-governance/view-model.js', 'pages/shezhi/shezhi.wxml',
    'pages/merchant/relation/index.js', 'pages/merchant/relation/index.wxml'];
  for (const rel of files) {
    const visible = read(rel).split('\n')
      .filter((line) => !/^\s*(\/\/|\*|\/\*|<!--)/.test(line))
      .filter((line) => /['"`>][^'"`<]*帖子/.test(line));
    assert.deepEqual(visible, [], rel + ' 仍有用户可见的「帖子」');
  }
});

test('核销据点有名字时只写名字,不拖「· #14」', () => {
  const view = require('../../utils/coop-invite-view.js');
  const row = { inviteType: 0, toType: 'merchant', toId: '11', topicId: '9', status: 0, requiresSupplyStation: true,
    supplyStationId: '14', supplyStationName: '人民广场音乐喷泉' };
  const text = view.supplyStationView(row).supplyStationText;
  if (text) assert.equal(text, '人民广场音乐喷泉');
  assert.doesNotMatch(read('pages/coop/invite/index.wxml'), /· #\{\{item\.supplyStationId\}\}/);
});

test('「查找原邀请请求」只在有待确认的邀请或正在查原请求时出现', () => {
  const wxml = read('pages/coop/invite/index.wxml');
  const block = wxml.slice(wxml.lastIndexOf('<view', wxml.indexOf('bindtap="discoverOriginalInvites"')), wxml.indexOf('bindtap="discoverOriginalInvites"'));
  assert.match(wxml, /<view class="send-receipt" wx:if="\{\{unknownTargets\.length \|\| receiptLoading \|\| receiptError \|\| receiptRows\.length\}\}">\s*<view class="inline-retry" bindtap="discoverOriginalInvites"/);
  assert.ok(block.length > 0);
});
