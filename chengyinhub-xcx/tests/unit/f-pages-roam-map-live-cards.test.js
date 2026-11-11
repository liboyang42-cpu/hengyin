'use strict';
// f-pages 第 9 组(2026-11-11):
//  - P-32 主地图底部横滑卡:附近活动 + 队伍,同一份入口读数(不另拉),空则不出;队伍进队伍详情,活动进详情页
//  - G-11 分享 / 邀请卡的码出现动效:bindload 后弹到位,只用过渡,减少动态效果直接到终态
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..');
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8');
const entryMap = require('../../utils/roam-entry-map.js');

test('teamView 只用 /api/team/nearby 已回字段,缺人数不写「0/0」', () => {
  const v = entryMap.teamView({ teamId: 7, title: '周末夜走', activityName: '外滩夜游', joinedCount: 2, maxMembers: 4, distance: 1200, leaderAvatar: 'a.png' }, (u) => 'https://img/' + u);
  assert.equal(v.key, 'team-7');
  assert.equal(v.kind, 'team');
  assert.equal(v.tag, '队伍');
  assert.equal(v.cover, 'https://img/a.png');
  assert.match(v.meta, /^外滩夜游 · 2\/4 人 · 距你 /);
  assert.equal(entryMap.teamView({ teamId: 8 }).meta, '');
  assert.equal(entryMap.teamView(null), null);
  assert.equal(entryMap.teamView({ title: '无 id' }), null);
});

test('主地图横滑卡只在 screen=map、商家条收起且有卡时渲染,与入口页同一张 entry-card', () => {
  const wxml = read('pages/roam/index.wxml');
  assert.match(wxml, /<scroll-view wx:if="\{\{screen=='map' && !shopStrip\.rows\.length && liveCards\.length\}\}" class="live-peek" scroll-x/);
  const block = wxml.slice(wxml.indexOf('class="live-peek"'), wxml.indexOf('</scroll-view>', wxml.indexOf('class="live-peek"')));
  assert.match(block, /class="entry-card live-card" wx:for="\{\{liveCards\}\}" wx:key="key" data-kind="\{\{item\.kind\}\}" data-id="\{\{item\.id\}\}"/);
  assert.match(block, /bindtap="onLiveCardTap"/);
  assert.match(block, /entry-card__tag--\{\{item\.kind === 'team' \? 'team'/);
  const js = read('pages/roam/index.js');
  assert.match(js, /const liveCards = \[\]\.concat\(view\.cards, \(this\._entryTeams \|\| \[\]\)\.map\(\(team\) => entryMap\.teamView\(team, getImgUrl\)\)\.filter\(Boolean\)\);/);
  const tap = js.slice(js.indexOf('onLiveCardTap(e) {'), js.indexOf('openEntryActivity(kind, id) {'));
  assert.match(tap, /if \(d\.kind === 'team'\) \{ this\._openEntryTeamById\(d\.id\); return; \}/);
  assert.match(tap, /'\/pages\/topic\/index\/index\?id=' \+ encodeURIComponent\(topicId\)/);
  assert.match(tap, /'\/pages\/activity\/detail\/index\?id=' \+ encodeURIComponent\(row\.id\)/);
  const wxss = read('pages/roam/index.wxss');
  assert.match(wxss, /\.live-peek\{ position:absolute;[^}]*bottom:calc\(250rpx \+ 246rpx \+ 19rpx \+ env\(safe-area-inset-bottom\)\)/);
  assert.match(wxss, /\.entry-card__tag--team \{ background: var\(--cy-play-role-player\); \}/);
});

test('分享 / 邀请卡的码:读好后弹到位,只用 transition,减少动态效果下无过渡', () => {
  const wxml = read('components/cy/share-card/index.wxml');
  assert.equal((wxml.match(/bindload="onQrLoad"/g) || []).length, 2, '邀请卡与普通卡两处码图都接 bindload');
  assert.match(wxml, /class="sc-qr sc-qr--inv \{\{qrReady \? 'sc-qr--ready' : ''\}\}"/);
  assert.match(wxml, /\{\{reducedMotion \? 'sc--reduced' : ''\}\}/);
  const wxss = read('components/cy/share-card/index.wxss');
  assert.match(wxss, /\.sc-qr \{ opacity: 0; transform: scale\(\.6\); transition: opacity var\(--cy-motion-standard\) var\(--cy-ease-out\), transform var\(--cy-motion-reveal, 450ms\) var\(--cy-ease-spring\); \}/);
  assert.match(wxss, /\.sc-qr--ready \{ opacity: 1; transform: scale\(1\); \}/);
  assert.match(wxss, /\.sc--reduced \.sc-qr \{ transition: none; \}/);
  assert.doesNotMatch(wxss, /@keyframes/);
  const js = read('components/cy/share-card/index.js');
  assert.match(js, /onQrLoad\(\) \{ if \(!this\.data\.qrReady\) this\.setData\(\{ qrReady: true \}\); \}/);
  assert.match(js, /qrReady: false, reducedMotion: readReducedMotion\(\),/, '每次打开重置,码图重新读好再出现');
});
