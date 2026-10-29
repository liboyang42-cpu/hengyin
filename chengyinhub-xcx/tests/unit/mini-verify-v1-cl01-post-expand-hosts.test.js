'use strict';
// CL-01(F-W5-01):长帖真正能看到全文。
// 卡片本身的「展开/收起」与 textLong 判据由 w5-club-topic-ui-tail.test.js 钉住;这里钉宿主链路:
//  · 只有俱乐部详情(talent/list 的落点)给卡片开 expandable-text,可原地展开;
//  · 广场 / 收藏 / 达人列表只出「展开」提示,点正文(emitDetail)进详情页;
//  · 详情页正文不夹行,全文可见。
// 负控:去掉 club/detail 的 expandable-text、或让列表宿主不再接 bind:detail,本文件必须真红。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const ROOT = path.resolve(__dirname, '../..');
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');
const LONG = '入社须知\n1. 准时集合\n2. 带水\n3. 注意安全';

function loadCard() {
  let definition;
  vm.runInNewContext(read('components/cy/post-card/index.js'), { Component: (v) => { definition = v; } });
  const card = Object.assign({}, definition.methods, {
    data: Object.assign({ expandableText: definition.properties.expandableText.value }, definition.data, { post: { id: 8, clubId: 3, memberId: 5, contents: LONG }, index: 2 }),
    events: [],
    setData(patch) { Object.assign(this.data, patch); },
    triggerEvent(name, detail) { this.events.push({ name, detail }); },
  });
  definition.observers['post.contents'].call(card, LONG);
  return card;
}

function postCardTag(wxml) {
  const match = wxml.match(/<cy-post-card\b[^>]*>/);
  assert.ok(match, '宿主里没有 cy-post-card');
  return match[0];
}

test('CL-01 列表卡(未开 expandable-text):长帖出「展开」提示,点正文抛 detail 带 postId/clubId', () => {
  const card = loadCard();
  assert.equal(card.data.textLong, true);
  assert.equal(card.data.expandableText, false);
  const wxml = read('components/cy/post-card/index.wxml');
  assert.match(wxml, /<view class="post-card__body" wx:if="\{\{post\.contents\}\}" catchtap="emitDetail"/);
  assert.match(wxml, /<text class="post-card__expand" wx:elif="\{\{textLong\}\}">展开<\/text>/, '列表态必须有展开提示');
  card.emitDetail();
  assert.deepEqual(JSON.parse(JSON.stringify(card.events)), [{ name: 'detail', detail: { index: 2, postId: 8, memberId: 5, clubId: 3 } }]);
});

test('CL-01 宿主链路:俱乐部详情原地展开;发现/收藏列表接 detail 进详情页', () => {
  assert.match(postCardTag(read('pages/club/detail/index.wxml')), /\bexpandable-text\b/, '俱乐部详情必须能原地展开');

  const hosts = [
    // 2026-10-29「动态」二级页已删;发现页全站流里的社区帖文进 square/detail
    { wxml: 'pages/talent/list/index.wxml', js: 'pages/talent/list/index.js', handler: 'onPostTap', target: /\/pages\/square\/detail\/index\?id=/ },
    { wxml: 'pages/mylike/mylike.wxml', js: 'pages/mylike/mylike.js', handler: 'goPost', target: /\/pages\/square\/detail\/index\?id=/ },
    { wxml: 'pages/talent/list/index.wxml', js: 'pages/talent/list/index.js', handler: 'onPostTap', target: /\/pages\/club\/detail\/index\?id=' \+ clubId \+ '&postId=/ },
  ];
  for (const host of hosts) {
    const tag = postCardTag(read(host.wxml));
    assert.doesNotMatch(tag, /\bexpandable-text\b/, host.wxml + ' 是列表，只出提示');
    assert.match(tag, new RegExp('bind:detail="' + host.handler + '"'), host.wxml + ' 必须接 detail');
    const js = read(host.js);
    const body = js.slice(js.search(new RegExp('\\n\\s+' + host.handler + '\\s*(?::\\s*function\\s*)?\\([^)]*\\)\\s*\\{')));
    assert.match(body.slice(0, 600), host.target, host.js + ' 的 ' + host.handler + ' 必须进详情页');
  }
});

test('CL-01 详情页正文不夹行:square/detail 的 .post-text 没有 line-clamp / 固定高度', () => {
  const wxml = read('pages/square/detail/index.wxml');
  assert.match(wxml, /<view class="post-text" wx:if="\{\{ info\.contents \}\}">\{\{info\.contents\}\}<\/view>/);
  const css = read('pages/square/detail/index.wxss');
  const rules = css.match(/[^{}]*\.post-text\b[^{}]*\{[^}]*\}/g) || [];
  assert.ok(rules.length > 0, '缺少 .post-text 样式');
  for (const rule of rules) {
    assert.doesNotMatch(rule, /line-clamp|max-height|text-overflow:\s*ellipsis/, '详情页正文不得夹行: ' + rule.trim());
  }
});

// #282 已修复详情正文分段；继续以硬断言防止列表展开与详情展示再次不一致。
test('CL-01 详情页正文保留换行(与卡片展开态一致)', () => {
  const css = read('pages/square/detail/index.wxss');
  const rules = css.match(/[^{}]*\.post-text\b[^{}]*\{[^}]*\}/g) || [];
  assert.ok(rules.some((rule) => /white-space:\s*pre-(wrap|line)/.test(rule)), '详情页正文要保留作者的分段');
});
