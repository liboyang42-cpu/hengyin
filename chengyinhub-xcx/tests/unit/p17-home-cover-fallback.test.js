'use strict';
// P-17:附近已展示真实主题；无封面保留中性底色，不恢复活动照片或 LIVE。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const wxml = fs.readFileSync(path.join(__dirname, '../../pages/index/index.wxml'), 'utf8');
const wxss = fs.readFileSync(path.join(__dirname, '../../pages/index/index.wxss'), 'utf8');
const { decorateFeedCards } = require('../../utils/index/feed-card');

function nearbySection(source) {
  const start = source.indexOf('<view id="sec-nearby"');
  const end = source.indexOf('<view id="sec-upcoming"', start);
  assert.ok(start >= 0 && end > start, '附近主题区块必须存在');
  return source.slice(start, end);
}

function assertNearbyCover(source, styles) {
  const nearby = nearbySection(source);
  assert.doesNotMatch(nearby, /index-figma\/nearby-card\.jpg|route_city_cover|home-route-city-placeholder/);
  assert.match(nearby, /<image class="v3-nearby-bg" wx:if="\{\{item\.cover\}\}" src="\{\{item\.cover\}\}"/);
  assert.match(styles, /\.v3-nearby-card\s*\{[^}]*background:\s*var\(--cy-color-bg-elevated\)/);
  assert.match(nearby, /class="v3-nearby-title ep2">\{\{item\.name\}\}/);
}

function assertNearbyThemeCard(source) {
  const nearby = nearbySection(source);
  assert.match(nearby, /wx:for="\{\{nearbyTopicList\}\}"/);
  assert.match(nearby, /bindtap="goTopic" data-id="\{\{item\.id\}\}"/);
  assert.match(nearby, /aria-label="\{\{item\.name\}\}"/);
  assert.doesNotMatch(nearby, /nearbyActivityList|goActivity|v3-nearby-live|item\.started|>LIVE</);
  assert.match(nearby, /class="v3-nearby-time" wx:if="\{\{item\.distance\}\}">\{\{item\.distance\}\}/);
  assert.match(nearby, /class="v3-nearby-time" wx:if="\{\{item\.feedTypeLabel\}\}">\{\{item\.feedTypeLabel\}\}/);
}

test('附近主题缺封面保留中性卡面和名称，不补通用照片', () => {
  assertNearbyCover(wxml, wxss);
  const cards = decorateFeedCards([
    { id: 17, name: '无图主题', imgArr: '', imgUrl: '', productType: 1 },
    { id: 18, name: '真实封面主题', imgUrl: 'theme.jpg', productType: 2 },
  ], src => 'cdn/' + src);
  assert.equal(cards[0].cover, '');
  assert.equal(cards[0].name, '无图主题');
  assert.equal(cards[0].feedTypeLabel, '城市定向');
  assert.equal(cards[1].cover, 'cdn/theme.jpg');
  assert.equal(cards[1].feedTypeLabel, '自由探索');
});

test('附近卡展示真实主题距离与类型，不恢复活动 LIVE 或活动入口', () => {
  assert.doesNotMatch(wxml, />LIVE</);
  assertNearbyThemeCard(wxml);
});

test('负控：恢复假封面或移除中性底色必须判红', () => {
  for (const fake of ['/images/route_city_cover.png', '/images/index-figma/nearby-card.jpg']) {
    const mutated = wxml.replace('src="{{item.cover}}"', 'src="{{item.cover || \'_FAKE_\'}}"'.replace('_FAKE_', fake));
    assert.notEqual(mutated, wxml, '负控必须真实变异');
    assert.throws(() => assertNearbyCover(mutated, wxss));
  }
  const mutated = wxss.replace(/(\.v3-nearby-card\s*\{[^}]*?)background: var\(--cy-color-bg-elevated\);/, '$1background: transparent;');
  assert.notEqual(mutated, wxss);
  assert.throws(() => assertNearbyCover(wxml, mutated));
});

test('负控：旧活动卡或隐藏无图主题名称必须判红', () => {
  for (const mutated of [
    wxml.replace('bindtap="goTopic" data-id="{{item.id}}"', 'bindtap="goActivity" data-id="{{item.id}}"'),
    wxml.replace('class="v3-nearby-title ep2"', 'class="v3-nearby-title ep2" wx:if="{{item.cover}}"'),
  ]) {
    assert.notEqual(mutated, wxml, '负控必须真实变异');
    assert.throws(() => { assertNearbyCover(mutated, wxss); assertNearbyThemeCard(mutated); });
  }
});
