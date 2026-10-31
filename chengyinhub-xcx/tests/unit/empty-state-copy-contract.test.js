const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');
const read = (relativePath) => fs.readFileSync(path.join(ROOT, relativePath), 'utf8');

// 2026-10-29 发现页帖文改成全站流:空态不再分「没有俱乐部 / 俱乐部没人发」两种说法,
// 只说这里没有帖文;业主规则不加副标题,也不能再把俱乐部当前置条件。
function assertTalentFeedEmptyCopy(wxml) {
  const empty = wxml.match(/<cy-empty class="feed-state"[^>]*\/>/);
  assert.ok(empty, '帖文流必须有空态');
  const title = (empty[0].match(/title="([^"]*)"/) || [])[1] || '';
  assert.match(title, /帖文/, '空态标题说明这里没有帖文');
  assert.doesNotMatch(title, /俱乐部/, '全站流的空态不能再拿俱乐部当前置条件');
  assert.doesNotMatch(empty[0], /\ssub=/, '不加副标题');
}

test('帖文 tab 空态只说明这里没有帖文，不再解释俱乐部来源', () => {
  assertTalentFeedEmptyCopy(read('pages/talent/list/index.wxml'));
  assert.doesNotMatch(read('pages/talent/list/index.js'), /feedEmptyTitle|feedEmptySub|noClub/);
});

test('negative control: 帖文空态标题改回“你还没有俱乐部”必须判红', () => {
  const source = read('pages/talent/list/index.wxml');
  const mutated = source.replace('<cy-empty class="feed-state" wx:else title="还没有帖文"', '<cy-empty class="feed-state" wx:else title="你还没有俱乐部"');
  assert.notEqual(mutated, source, '负控必须命中帖文空态标题');
  assert.throws(() => assertTalentFeedEmptyCopy(mutated), assert.AssertionError);
  const withSub = source.replace('title="还没有帖文" />', 'title="还没有帖文" sub="加入俱乐部后可见" />');
  assert.notEqual(withSub, source);
  assert.throws(() => assertTalentFeedEmptyCopy(withSub), assert.AssertionError);
});
