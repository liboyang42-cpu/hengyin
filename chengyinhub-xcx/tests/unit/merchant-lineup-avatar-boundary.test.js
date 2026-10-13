const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const WXML_PATH = path.resolve(__dirname, '../../pages/topic/merchantinfo/merchantinfo.wxml');
const WXML = fs.readFileSync(WXML_PATH, 'utf8');

function hasNullableAvatarFallback(source) {
  return /<cy-avatar\b[^>]*\bsrc="\{\{item\.avatar \|\| ''\}\}"/.test(source);
}

test('商家承接页不再渲染阵容头像；头像边界在未来重加时仍须归一', () => {
  assert.doesNotMatch(WXML, /info\.lineup|item\.avatar/);
  assert.equal(hasNullableAvatarFallback(`<cy-avatar src="{{item.avatar || ''}}" />`), true);
});

test('负控：直接透传可空头像与恢复旧阵容都会被拒绝', () => {
  assert.equal(hasNullableAvatarFallback('<cy-avatar src="{{item.avatar}}" />'), false);
  const oldLayout = WXML + '<cy-avatar src="{{item.avatar}}" />';
  assert.throws(() => assert.doesNotMatch(oldLayout, /info\.lineup|item\.avatar/), assert.AssertionError);
});
