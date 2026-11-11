'use strict';
// f-pages 第 7 组(2026-11-11):死页接入口。
//  - P0-79 官方路线:漫游护照玩法大卡,只在 /api/preset/route/list 回 ≥1 条时出卡,点卡进列表页
//  - P0-108/P0-109 据点页:评价区保留,表单改成半屏弹层(cy-sheet fixed="half"),只对已完成挑战的玩家出入口
//  - OFF-FAV-01 我的收藏:「据点」页签读 GET /api/city/nodes/favorites,行进 poi-detail,取消走 toggle 并回包 favorited=false 才摘卡
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..');
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8');

test('官方路线入口:护照大卡只认服务端路线数,点卡进 official-routes', () => {
  const js = read('pages/roam/index.js');
  const body = js.slice(js.indexOf('_loadOfficialRoutes() {'), js.indexOf('retryPassport() {'));
  assert.match(body, /req\('\/api\/preset\/route\/list', 'GET'/);
  assert.match(body, /if \(!routes\.length \|\| !Array\.isArray\(passport\.plays\)/, '没有路线不摆卡');
  assert.match(body, /key: 'routes', icon: 'map', title: '官方路线', sub: routes\.length \+ ' 条路线'/);
  assert.match(js, /if \(key === 'routes'\) \{[^\n]*wx\.navigateTo\(\{ url: '\/subpackageRoam\/official-routes\/index'/);
  assert.match(js, /this\._loadActiveRoamEvents\(\);\s*this\._loadOfficialRoutes\(\);/);
  // 豁免表里不再有它
  assert.doesNotMatch(read('tests/unit/nav-route-exists.test.js'), /\['\/subpackageRoam\/official-routes\/index'/);
});

test('据点页:评价区保留,表单在半屏弹层里,入口只对已完成挑战出', () => {
  const D = 'subpackageMember/components/cy/scene-roam-poi-detail/';
  const wxml = read(D + 'index.wxml');
  assert.match(wxml, /<text class="poi-section__title">玩家评价<\/text>/);
  assert.match(wxml, /<cy-btn wx:if="\{\{node\.completed\}\}" class="poi-review-open" variant="secondary" bind:tap="openReviewSheet">写评价<\/cy-btn>/);
  assert.match(wxml, /<cy-sheet show="\{\{reviewSheetShow\}\}" no-depth title="评价据点" fixed="half" footer bind:close="closeReviewSheet">/);
  const sheet = wxml.slice(wxml.indexOf('<cy-sheet show="{{reviewSheetShow}}"'), wxml.indexOf('</cy-sheet>'));
  assert.match(sheet, /class="poi-rating-pick"/);
  assert.match(sheet, /<textarea class="poi-review-input"/);
  assert.match(sheet, /<view slot="footer">\s*<cy-btn loading="\{\{reviewSubmitting\}\}" disabled="\{\{reviewSubmitting\}\}" bind:tap="submitReview">提交评价<\/cy-btn>/);
  // 无说明文案
  assert.doesNotMatch(wxml, /评价可跳过/);
  const js = read(D + 'index.js');
  assert.match(js, /openReviewSheet\(\) \{ if \(this\.data\.node && this\.data\.node\.completed && !this\.data\.reviewSubmitting\) this\.setData\(\{ reviewSheetShow: true \}\) \}/);
  assert.match(js, /toast\('评价已提交'\); this\.setData\(\{ reviewSheetShow: false \}\); this\._load\(\)/);
  assert.equal(JSON.parse(read(D + 'index.json')).usingComponents['cy-sheet'], '/components/cy/sheet/index');
});

test('我的收藏「据点」页签:读 favorites,行进 poi-detail,取消收藏按 toggle 回包摘卡', () => {
  const js = read('pages/mylike/mylike.js');
  assert.match(js, /\{ key: 'posts', label: '帖文' \}, \{ key: 'topics', label: '主题' \}, \{ key: 'nodes', label: '据点' \}/);
  // 社区读闸关时仍有「据点」
  assert.match(js, /favoriteTabs: \[\{ key: 'topics', label: '主题' \}, \{ key: 'nodes', label: '据点' \}\], activeFavoriteTab: 'topics'/);
  assert.match(js, /url: '\/api\/city\/nodes\/favorites',\s*method: 'GET'/);
  assert.match(js, /url: '\/api\/city\/nodes\/' \+ encodeURIComponent\(requestKey\) \+ '\/favorite',\s*method: 'POST'/);
  assert.match(js, /res\.data\.favorited === false\) \{\s*that\._recordFavoriteRemoval\('nodes', requestKey\);/);
  assert.match(js, /wx\.navigateTo\(\{ url: '\/subpackageRoam\/poi-detail\/index\?poiId=' \+ encodeURIComponent\(id\) \}\)/);
  assert.match(js, /if \(that\.data\.activeFavoriteTab === 'nodes'\) return;/, '据点无分页');
  const wxml = read('pages/mylike/mylike.wxml');
  const start = wxml.indexOf("activeFavoriteTab === 'nodes'");
  const block = wxml.slice(start, wxml.indexOf('已加载全部据点', start));
  assert.match(block, /title="暂无收藏的据点"/);
  assert.match(block, /bindtap="goNode" data-id="\{\{item\.id\}\}"/);
  assert.match(block, /bindtap="nodeUnfavorite" data-id="\{\{item\.id\}\}" aria-label="取消收藏"/);
  assert.match(block, /<cy-icon class="favorite-card__action-heart" name="heart" size="32" \/>/);
});

test('据点评价半屏嵌在 scene 面板里:no-depth 不进叠层登记,外层 scene 不被压暗 / pointer-events:none(rig 实测原先整层变暗点不动)', () => {
  const behavior = require('../../behaviors/sheet-depth.js');
  const registry = require('../../utils/sheet-depth.js');
  registry._reset();
  const mk = (noDepth) => ({ data: { _under: false, noDepth }, getPageId: () => 'p', setData(p) { Object.assign(this.data, p) },
    _applySheetDepth: behavior.methods._applySheetDepth });
  const scene = mk(false), nested = mk(true), plain = mk(false);
  behavior.observers.show.call(scene, true);
  behavior.observers.show.call(nested, true);
  assert.equal(scene.data._under, false, 'no-depth 的嵌套弹层打开时外层仍是顶层');
  behavior.observers.show.call(plain, true);
  assert.equal(scene.data._under, true, '普通弹层照旧把下面那层压下去');
  behavior.observers.show.call(nested, false);
  registry._reset();
  const js = read('components/cy/sheet/index.js');
  assert.match(js, /noDepth: \{ type: Boolean, value: false \},/);
});
