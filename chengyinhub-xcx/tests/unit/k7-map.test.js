'use strict';

// 第十二轮 k7-map:j4 第二轮的地图与队伍发现(N1 N2 N3 N4 N5 N6 N8 N9 F14 F15 F16)。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '../..');
const PAGE_MODULE = path.join(ROOT, 'pages/roam/index.js');
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8');
const pins = require('../../pages/roam/photo-pins.js');

function loadRoamPage() {
  let definition;
  const prev = { Page: global.Page, getApp: global.getApp, getCurrentPages: global.getCurrentPages, wx: global.wx };
  try {
    global.Page = (config) => { definition = config; };
    global.getApp = () => ({ globalData: {}, getUserID: () => '' });
    global.getCurrentPages = () => [];
    global.wx = { getStorageSync: () => undefined };
    delete require.cache[require.resolve(PAGE_MODULE)];
    require(PAGE_MODULE);
  } finally { Object.assign(global, prev); }
  const page = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)) });
  page.setData = function (patch, cb) {
    Object.keys(patch).forEach((k) => {
      const parts = k.split('.');
      let o = this.data;
      parts.slice(0, -1).forEach((x) => { o = o[x]; });
      o[parts[parts.length - 1]] = patch[k];
    });
    if (cb) cb();
  };
  page._genNpcIcons = () => Promise.resolve(false);
  page._icons = { 'pin-dark': 'dark.png', 'pin-dot': 'dot.png', 'state-actionable': 'act.png', player: 'me.png' };
  page._loadShopStripVisitors = () => {};
  page._mapCtx = { moveToLocation() {} };
  return page;
}

function shop(id, lat, lng) {
  return { id, name: '店' + id, lat, lng, cat: 'merchant', regId: id, state: 'seen', photo: '' };
}

test('N1:点报名商家的图钉开横滑卡,停在这一家;不再先出地点卡', () => {
  const page = loadRoamPage();
  page._pois = [shop(1, 31.21, 121.41), shop(2, 31.22, 121.42), shop(3, 31.215, 121.415)];
  page.onMarkerTap({ detail: { markerId: 3 } });
  assert.equal(page.data.shopStrip.rows.length, 3);
  assert.equal(page.data.shopStrip.idx, 2);
  assert.equal(page.data.shopStrip.rows[2].poiId, '3');
  assert.equal(page.data.placeCard, null);
  assert.equal(page._pinFocusId, 3, '地图飞到并放大这一枚');
});

test('N1:第 9 家以后的商家也能被点开,它会被放进条里', () => {
  const page = loadRoamPage();
  page._pois = Array.from({ length: 10 }, (_, i) => shop(i + 1, 31.2 + i * 0.001, 121.4));
  page.onMarkerTap({ detail: { markerId: 10 } });
  assert.equal(page.data.shopStrip.rows.length, 8);
  assert.equal(page.data.shopStrip.rows[page.data.shopStrip.idx].poiId, '10');
});

test('N1:不是报名商家的地点照旧出地点卡', () => {
  const page = loadRoamPage();
  page._pois = [{ id: 700005, name: '据点', lat: 31.21, lng: 121.41, cat: 'landmark', state: 'seen', _roamId: 5, isCityNode: true, merchantId: 9, photo: '' }];
  page.onMarkerTap({ detail: { markerId: 700005 } });
  assert.equal(page.data.shopStrip.rows.length, 0);
  assert.ok(page.data.placeCard);
});

test('N1:「附近 N 个地点」可点,开横滑卡;命中区至少 88rpx', () => {
  const page = loadRoamPage();
  page._pois = [shop(1, 31.21, 121.41), shop(2, 31.22, 121.42)];
  page.onPinCountTap();
  assert.equal(page.data.shopStrip.rows.length, 2);
  assert.equal(page.data.shopStrip.idx, 0);
  const wxml = read('pages/roam/index.wxml');
  assert.match(wxml, /class="pin-count"[^>]*bindtap="onPinCountTap"[^>]*aria-role="button"/);
  const css = read('pages/roam/index.wxss');
  assert.match(css, /\.roam-topstack > \.pin-count\{[^}]*pointer-events:auto;[^}]*min-height:88rpx/);
});

test('N2:贴边图钉收在搜索栏下、底部卡片上,左右留出半枚图钉', () => {
  const REGION = { southwest: { latitude: 31.20, longitude: 121.40 }, northeast: { latitude: 31.22, longitude: 121.42 } };
  const safe = { top: 144, bottom: 270, width: 390, height: 844 };
  const above = pins.placeInView(31.2205, 121.41, REGION, safe);
  assert.equal(above.kind, 'edge');
  const yTop = (31.22 - above.lat) / 0.02 * 844;
  assert.ok(yTop >= 144, '不压在搜索栏 / 胶囊上:' + yTop);
  const below = pins.placeInView(31.1995, 121.41, REGION, safe);
  const yBottom = (31.22 - below.lat) / 0.02 * 844;
  assert.ok(yBottom <= 844 - 270, '不藏在底部卡片和三键后面:' + yBottom);
  const left = pins.placeInView(31.21, 121.3995, REGION, safe);
  const x = (left.lng - 121.40) / 0.02 * 390;
  assert.ok(x >= 24, '左边不出屏一半:' + x);
  // 不给安全区时与原来一样
  const old = pins.placeInView(31.2205, 121.41, REGION);
  assert.ok(Math.abs(old.lat - (31.22 - 0.02 * 0.06)) < 1e-9);
  const page = loadRoamPage();
  page._win = { w: 390, h: 844 };
  const area = page._pinSafeArea();
  assert.equal(area.top, page.data.promptTop);
  assert.ok(area.bottom >= 260 && area.bottom <= 280, String(area.bottom));
});

test('N3:雪点更淡更散,不和地点白点同一个样子', () => {
  const src = read('subpackageRoam/components/map-weather/index.js');
  assert.match(src, /const SNOW = 'rgba\(232,238,250,\.34\)'/);
  assert.match(src, /ctx\.arc\(p\.x, p\.y, 2 \+ p\.s \* 3, 0, 7\)/);
});

test('N4:没有真实队伍数时不写「附近正在组队」', () => {
  const js = read('pages/roam/index.js');
  assert.doesNotMatch(js, /附近正在组队/);
  const page = loadRoamPage();
  assert.equal(page.data.introTeamsLive, '');
});

test('N5:同一主题的几场卡片写出场次开始时刻(北京时间)', () => {
  const M = require('../../utils/roam-entry-map.js');
  const a = M.activityView({ kind: 'activity', id: 9, name: 'E2E', startDate: '2026-11-16 19:00:00', endDate: '2099-11-16 22:00:00', distance: 2 }, []);
  const b = M.activityView({ kind: 'activity', id: 10, name: 'E2E', startDate: '2026-11-17 10:30:00', endDate: '2099-11-17 22:00:00', distance: 2 }, []);
  assert.equal(a.meta, '11月16日 19:00 · 距你 2m');
  assert.notEqual(a.meta, b.meta);
  assert.equal(M.sessionWhen({ kind: 'topic', startDate: '2026-11-16 19:00:00' }), '', '主题卡没有场次时刻');
  assert.equal(M.sessionWhen({ kind: 'activity' }), '');
});

test('N6:商家身份的搜索地图用商家浅色,玩家仍是深色', () => {
  const wxml = read('pages/searchmap/index.wxml');
  assert.match(wxml, /<view class="\{\{merchantView \? 'theme-merchant' : 'theme-dark'\}\}">/);
  assert.match(read('pages/searchmap/index.js'), /merchantView: isMerchantView\(\)/);
  assert.match(read('pages/searchmap/index.wxss'), /@import '\.\.\/\.\.\/style\/merchant-light-scope\.wxss';/);
});

test('N8:队伍确认框全角标点,解散框不再复述第一条后果', () => {
  const { DANGER_ACTIONS } = require('../../utils/danger-actions.js');
  ['team.disband', 'team.kick', 'team.reject', 'team.quit', 'team.quit-leader'].forEach((k) => {
    const a = DANGER_ACTIONS[k];
    const all = [a.title, a.content || '', (a.alt && a.alt.hint) || ''].concat((a.consequences || []).map((c) => c.text)).join('');
    assert.doesNotMatch(all, /[,;?]/, k + ' 有半角标点:' + all);
  });
  assert.equal(DANGER_ACTIONS['team.disband'].content, undefined);
  assert.equal(DANGER_ACTIONS['team.reject'].title, '拒绝「{name}」的申请？');
});

test('N9:整行大数字只给「点亮坐标」/「玩法完成」,用时不再是被「继续」压住的巨字', () => {
  const css = read('pages/play/index.wxss');
  assert.doesNotMatch(css, /\.finsheet \.fst:first-child/);
  assert.match(css, /\.finsheet \.fst--hero \.fst__v\{ font-size:216rpx;/);
  const wxml = read('pages/play/index.wxml');
  assert.match(wxml, /<view class="fst fst--hero" wx:if="\{\{doneCount > 0\}\}">/);
  assert.match(wxml, /<view class="fst fst--s" wx:if="\{\{reviewElapsed\}\}">/);
});

test('F14:附近活动卡、读数卡和店名贴纸的字跟手机字号走', () => {
  const css = read('pages/roam/index.wxss');
  ['.entry-card__tag{', '.entry-card__title{', '.entry-card__meta{', '.pcard__title{', '.pstat__l{', '.entry-peek__head{'].forEach((sel) => {
    const i = css.indexOf(sel);
    assert.ok(i >= 0, sel);
    assert.match(css.slice(i, css.indexOf('}', i)), /font-size:calc\(\d+rpx \* var\(--cy-type-scale, 1\)\)/, sel);
  });
  assert.match(read('components/cy/free-map/index.wxss'), /\.fmap-xpin__tag \{[^}]*font-size: calc\(22rpx \* var\(--cy-type-scale, 1\)\)/);
});

test('F15:没起名的队伍按队长昵称区分', () => {
  const src = read('subpackageRoam/my-teams/index.js');
  assert.match(src, /leader \? leader \+ '的队伍' : '一起出发的玩家队伍'/);
  const T = require('../../subpackageRoam/utils/map-team.js');
  const a = T.decorateTeam({ teamId: 1, title: '', leaderName: '阿青', joinedCount: 1, maxMembers: 3 });
  const b = T.decorateTeam({ teamId: 2, title: '', leaderName: '小周', joinedCount: 1, maxMembers: 2 });
  assert.equal(a.name, '阿青的队伍');
  assert.notEqual(a.name, b.name);
});

test('F16:没头像的队伍图钉与侧栏写队名首字;落在站点旁时让开站号', () => {
  const kit = require('../../pages/play/utils/team-trail.js');
  const out = kit.teamPins([{ key: 'k1', name: '晨光队', avatar: '', color: '#4ADE80', last: { latitude: 31.23, longitude: 121.47 } },
    { key: 'k2', name: '夜航队', avatar: 'https://img/a.png', color: '#60A5FA', last: { latitude: 31.24, longitude: 121.48 } }]);
  assert.equal(out[0].initial, '晨');
  assert.equal(out[1].initial, '');
  const wxml = read('components/cy/free-map/index.wxml');
  assert.match(wxml, /<cover-view wx:elif="\{\{item\.initial\}\}" class="fmap-tpin__ini"/);
  assert.match(read('pages/play/index.wxml'), /<text wx:elif="\{\{item\.initial\}\}" class="team-rail__ini"/);
  assert.match(read('pages/play/index.js'), /initial: t\.avatar \? '' : teamTrailKit\.initialOf\(t\.name\)/);
  const fm = read('components/cy/free-map/index.js');
  assert.match(fm, /anchorX: nearNode && !this\.data\.compact \? TEAM_PIN_NUDGE_PX : 0/);
});
