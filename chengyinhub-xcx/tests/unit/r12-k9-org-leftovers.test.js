'use strict';
// 第十二轮 k9-org-leftovers:协作详情终态与联系方式行(p2 N-14)、受邀承接方不再出第二个扫码(p2 N-17)
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..', '..');
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');

test('p2 N-14:已结束的邀约尾栏写真实状态,不再一律「已失效」', () => {
  const wxml = read('pages/coop/invite-detail/index.wxml');
  assert.ok(!wxml.includes('这条邀约已失效，没有可执行的操作'));
  assert.match(wxml, /这条邀约\{\{statusText \|\| '已结束'\}\}/);
  const js = read('pages/coop/invite-detail/index.js');
  assert.match(js, /statusText: STATUS_LABEL\[status\]/);
});

test('p2 N-14:已接受却没下发联系方式时联系方式行整行不出,不复述下方「联系合作方」', () => {
  const wxml = read('pages/coop/invite-detail/index.wxml');
  assert.ok(!wxml.replace(/<!--[\s\S]*?-->/g, '').includes('可用下方「联系合作方」站内沟通'));
  assert.match(wxml, /class="kv meta-contact" wx:if="\{\{invite\.partnerContact \|\| invite\.status !== 1\}\}"/);
});

test('p2 N-17:快捷格为空时整排不渲染', () => {
  const wxml = read('pages/topic/components/project-join/index.wxml');
  assert.match(wxml, /<view class="qa" wx:if="\{\{ quickActions\.length \}\}">/);
});
