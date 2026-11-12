'use strict';
// f-pages P-20 / M-33(2026-11-11):本人主页(玩家和商家)帖文 tab 有发帖入口,点开是全站统一的全屏发帖弹窗;
// 入口放在状态块之前,空态上方也有;只认服务端 communityPostWrite,他人主页不出。
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..');
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8');
const DIR = 'subpackageMember/components/cy/profile/';

test('个人主页组件跨分包挂同一个 cy-post-compose,带占位', () => {
  const json = JSON.parse(read(DIR + 'index.json'));
  assert.equal(json.usingComponents['cy-post-compose'], '/pages/square/components/cy/post-compose/index');
  assert.equal(json.componentPlaceholder['cy-post-compose'], 'view', '跨分包引用必须带占位');
});

test('帖文 tab 的发帖入口在状态块之前(空态上方也有),只在 isSelf && canCompose 出现', () => {
  const wxml = read(DIR + 'index.wxml');
  const tab = wxml.slice(wxml.indexOf("activeTab === 'posts'"));
  const entry = tab.indexOf('class="post-composer-collapsed"');
  const empty = tab.indexOf('<cy-empty class="pc-feed-state"');
  assert.ok(entry > 0 && empty > 0 && entry < empty, '入口要在空态之前');
  assert.match(tab, /class="post-composer-collapsed" wx:if="\{\{isSelf && canCompose\}\}" bindtap="openComposer"/);
  assert.match(wxml, /<cy-post-compose wx:if="\{\{isSelf\}\}" show="\{\{composerOpen\}\}" bind:close="closeComposer" bind:published="onComposePublished" \/>/);
  // D-91(业主 2026-11-11):观看者是商家时同样走日间(viewerLight)。商家日间页要包 theme-merchant 才取得到日间 token
  assert.match(wxml, /<view class="\{\{\(isMerchantView \|\| viewerLight\) \? 'theme-merchant' : ''\}\}"><cy-post-compose/);
  // 无副标题/说明文案
  assert.doesNotMatch(tab.slice(0, empty), /发布一次|在这里分享|说明/);
});

test('canCompose 只来自 app.canUseCommunity(true),发布成功回读帖文流', () => {
  const js = read(DIR + 'index.js');
  assert.match(js, /syncComposeAccess: function \(\) \{\s*var can = !!this\.data\.isSelf && typeof app\.canUseCommunity === 'function' && app\.canUseCommunity\(true\);/);
  // 启动时 features 与本页读取并发:社区开关回来后 _syncCommunityProfile 的 settle 再同步一次(rig 实测原先停在 false)
  const settle = js.slice(js.indexOf('_syncCommunityProfile() {'), js.indexOf('initializeProfile: function'));
  assert.match(settle, /const tabs = profileTabs\(this\.data\.isSelf, this\.data\.isMerchantView\);\s*this\.syncComposeAccess\(\);/);
  assert.match(settle, /if \(pending && typeof pending\.then === 'function'\) pending\.then\(settle\);/);
  assert.match(js, /that\.buildDerivedState\(\);\s*that\.syncComposeAccess\(\);/);
  assert.match(js, /openComposer: function \(\) \{\s*if \(!this\.data\.isSelf \|\| !this\.data\.canCompose\) return;/);
  assert.match(js, /onComposePublished: function \(\) \{\s*this\.setData\(\{ composerOpen: false \}\);\s*this\.reloadFeed\(\);/);
  // 退出登录清空读态时入口一并收起
  assert.match(js, /canCompose: false, composerOpen: false,/);
});

test('入口样式与商家「发现」页同一套 post-composer-* 类', () => {
  const wxss = read(DIR + 'index.wxss');
  const rel = read('pages/merchant/relation/index.wxss');
  ['.post-composer-collapsed', '.post-composer-col', '.post-composer-who', '.post-composer-tools'].forEach((cls) => {
    assert.ok(wxss.includes(cls), cls);
    assert.ok(rel.includes(cls), cls);
  });
});
