'use strict';

// 第九轮 m1 第 2–6 项:漫游照片图钉、地点卡、卡片联动、远近密度、贴边与拖动云层。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '../..');
const PAGE_MODULE = path.join(ROOT, 'pages/roam/index.js');
const pins = require('../../pages/roam/photo-pins.js');
const { placeShare, placeShareFromEvent } = require('../../utils/place-share.js');
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8');

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
  page.setCalls = [];
  page.setData = function (patch, cb) {
    this.setCalls.push(patch);
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
  return page;
}

const REGION = { southwest: { latitude: 31.20, longitude: 121.40 }, northeast: { latitude: 31.22, longitude: 121.42 } };
const MOCK = '/images/index-figma/nearby-card.jpg';

function poi(id, lat, lng, extra) {
  return Object.assign({ id, name: '店' + id, lat, lng, cat: 'landmark', state: 'seen', photo: '' }, extra || {});
}

test('真照片才算照片;演示点与占位图不算', () => {
  assert.equal(pins.pinPhoto({ photo: 'https://x/y.jpg' }, MOCK), 'https://x/y.jpg');
  assert.equal(pins.pinPhoto({ photo: MOCK }, MOCK), '');
  assert.equal(pins.pinPhoto({}, MOCK), '');
});

test('参与商铺 = 报名商家或挂了商家的据点;演示点不算', () => {
  assert.equal(pins.isParticipating({ cat: 'merchant', regId: 3 }), true);
  assert.equal(pins.isParticipating({ isCityNode: true, merchantId: 8 }), true);
  assert.equal(pins.isParticipating({ isCityNode: true }), false);
  assert.equal(pins.isParticipating({ cat: 'merchant', regId: 3, demo: true }), false);
  assert.equal(pins.isParticipating({ cat: 'landmark' }), false);
});

test('屏内照常,近出屏贴边(坐标夹回视口内),更远的不画', () => {
  assert.equal(pins.placeInView(31.21, 121.41, REGION).kind, 'in');
  const edge = pins.placeInView(31.21, 121.425, REGION);
  assert.equal(edge.kind, 'edge');
  assert.ok(edge.lng < 121.42 && edge.lng > 121.41);
  assert.equal(pins.placeInView(31.21, 121.5, REGION).kind, 'far');
  assert.equal(pins.placeInView(31.3, 121.41, REGION).kind, 'far');
  assert.equal(pins.placeInView(31.21, 121.5, null).kind, 'in', '没有视口时不丢点');
});

test('拉远 = 小圆点;小圆点按屏幕格合并真实点位,不是均匀网格', () => {
  assert.equal(pins.densityMode(14), 'dots');
  assert.equal(pins.densityMode(15), 'pins');
  const pts = [poi(1, 31.2101, 121.4101), poi(2, 31.21011, 121.41012), poi(3, 31.215, 121.415), poi(4, 31.5, 121.9)];
  const dots = pins.bucketDots(pts, REGION, { width: 390, height: 844 });
  assert.equal(dots.length, 2, '挨着的两点并成一格,视口外的不算');
  assert.equal(dots.find((d) => d.count === 2).count, 2);
  assert.equal(pins.nearbyCountLabel(9), '');
  assert.equal(pins.nearbyCountLabel(243), '附近 243 个地点');
});

test('漫游 marker:照片图钉 + 仅参与商铺贴店名;没照片是深色圆;远处的不画', () => {
  const page = loadRoamPage();
  page.data.center = { lat: 31.21, lng: 121.41 };
  page._mapScale = 16;
  page._pinRegion = REGION;
  page._pois = [
    // 第九轮 m2 补充第 2 项起图钉互不重叠:四个点在屏上拉开,各自放得下
    poi(1, 31.205, 121.405, { photo: 'https://img/1.jpg', cat: 'merchant', regId: 11 }),
    poi(2, 31.205, 121.413, { photo: 'https://img/2.jpg' }),
    poi(3, 31.213, 121.405),
    poi(4, 31.21, 121.425, { photo: 'https://img/4.jpg' }),
    poi(5, 31.21, 121.6, { photo: 'https://img/5.jpg' }),
    poi(6, 31.213, 121.413, { photo: MOCK, imgUrl: MOCK }),
  ];
  page._syncMarkers();
  const byId = Object.fromEntries(page.data.markers.map((m) => [m.id, m]));
  assert.ok(!byId[5], '更远的不画');
  assert.equal(byId[4].width, pins.EDGE_SIZE, '近出屏的缩小贴边');
  assert.ok(byId[4].longitude < 121.42);
  [1, 2, 3, 6].forEach((id) => { assert.equal(byId[id].iconPath, 'dark.png'); assert.equal(byId[id].callout, undefined, '地图上不挂文字气泡'); });
  const pinById = Object.fromEntries(page.data.explorePins.map((p) => [p.id, p]));
  assert.deepEqual(Object.keys(pinById).map(Number).sort(), [1, 2, 4]);
  assert.deepEqual([pinById[4].size, pinById[4].label], [pins.EDGE_SIZE, ''], '贴边的是小照片,不挂店名');
  assert.equal(pinById[1].label, '店1', '参与商铺贴店名');
  assert.equal(pinById[2].label, '', '其余只有照片');
  assert.equal(pinById[1].img, 'https://img/1.jpg');
  assert.equal(byId[1].customCallout.anchorY, pinById[1].size, '照片 callout 正好盖在圆上');
  assert.ok(!pinById[6], '占位图不当照片');
});

test('同一份 markers 不重发 setData;拉远换成小圆点与附近点数', () => {
  const page = loadRoamPage();
  page.data.center = { lat: 31.21, lng: 121.41 };
  page._mapScale = 16; page._pinRegion = REGION;
  page._pois = Array.from({ length: 12 }, (_, i) => poi(i + 1, 31.201 + i * 0.0015, 121.401 + i * 0.0015));
  page._syncMarkers();
  const n = page.setCalls.length;
  page._syncMarkers();
  assert.equal(page.setCalls.length, n, '内容不变不 setData');
  page._mapScale = 13;
  page._syncMarkers();
  assert.ok(page.data.markers.length > 0);
  assert.ok(page.data.markers.every((m) => m.iconPath === 'dot.png'));
  assert.equal(page.data.pinCountLabel, '附近 12 个地点');
});

test('点图钉:地图居中放大 + 地点卡;点卡片才进原来的详情;导航与分享的落点', () => {
  const page = loadRoamPage();
  page.data.center = { lat: 31.2, lng: 121.4 };
  page._player = { lat: 31.21, lng: 121.41 };
  const moved = [];
  page._mapCtx = { moveToLocation: (o) => moved.push(o) };
  const opened = [];
  page.openScene = (id, params) => opened.push([id, params]);
  page._pois = [poi(700005, 31.212, 121.41, { _roamId: 5, isCityNode: true, merchantId: 9, photo: 'https://img/5.jpg', address: '山阴路 128 号' })];
  page.onMarkerTap({ detail: { markerId: 700005 } });
  assert.deepEqual(moved, [{ latitude: 31.212, longitude: 121.41 }]);
  const card = page.data.placeCard;
  assert.equal(card.name, '店700005');
  assert.equal(card.address, '山阴路 128 号');
  assert.equal(card.distText, '223 米');
  assert.deepEqual([card.shareKind, card.shareId], ['poi', '5']);
  assert.equal(page.data.explorePins[0].size, pins.PIN_SIZE_FOCUS, '选中的图钉放大');
  assert.deepEqual(opened, []);
  page.onPlaceCardOpen();
  assert.deepEqual(opened, [['roam-poi-detail', { poiId: 5 }]]);
  assert.equal(page.data.placeCard, null);
});

test('横滑卡开着时点图钉滑到它那张卡;滑卡飞到对应图钉', () => {
  const page = loadRoamPage();
  const moved = [];
  page._mapCtx = { moveToLocation: (o) => moved.push(o) };
  page._pois = [poi(1, 31.21, 121.41, { cat: 'merchant', regId: 1 }), poi(2, 31.22, 121.42, { cat: 'merchant', regId: 2 })];
  page._loadShopStripVisitors = () => {};
  page.openShopStrip();
  assert.equal(page.data.shopStrip.rows.length, 2);
  page.onMarkerTap({ detail: { markerId: 2 } });
  assert.equal(page.data.shopStrip.idx, 1);
  assert.equal(page.data.placeCard, null, '横滑卡开着时不叠地点卡');
  page.onShopStripSwipe({ detail: { current: 0 } });
  assert.deepEqual(moved[moved.length - 1], { latitude: 31.21, longitude: 121.41 });
  assert.equal(page._pinFocusId, 1);
});

test('分享落在据点深链页;只认数字 id;封面不用地图截屏', () => {
  assert.deepEqual(placeShare('poi', '5', '咖啡', 'https://img/5.jpg'),
    { title: '咖啡', path: '/subpackageRoam/poi-detail/index?poiId=5', imageUrl: 'https://img/5.jpg' });
  assert.equal(placeShare('merchant', '7', '店', '').path, '/subpackageRoam/poi-detail/index?merchantId=7');
  assert.ok(placeShare('merchant', '7', '店', '').imageUrl, '没有照片时用固定封面');
  assert.equal(placeShare('poi', 'x', '', ''), null);
  assert.equal(placeShareFromEvent({ from: 'menu' }), null);
  const evt = { from: 'button', target: { dataset: { shareType: 'place', shareKind: 'poi', shareId: '5', shareName: 'A' } } };
  assert.equal(placeShareFromEvent(evt).path, '/subpackageRoam/poi-detail/index?poiId=5');
  const page = loadRoamPage();
  assert.equal(page.onShareAppMessage(evt).path, '/subpackageRoam/poi-detail/index?poiId=5');
  const deep = read('subpackageRoam/poi-detail/index.js');
  assert.match(deep, /merchantId/);
});

test('拖动:按下云层显形,松手后按地图位移的 0.35 倍漂移;减弱动态时不出现', () => {
  const page = loadRoamPage();
  page.data.screen = 'map';
  page._cloudDragBegin();
  assert.equal(page.data.cloudOn, true);
  const next = { southwest: { latitude: 31.20, longitude: 121.41 }, northeast: { latitude: 31.22, longitude: 121.43 } };
  page._fogScreenSize = { width: 390, height: 844 };
  page._cloudDragEnd(REGION, next);
  assert.equal(page.data.cloudOn, false);
  assert.match(page.data.cloudStyle, /translate3d\(-68px,0px,0\)/, '地图向东走半屏,云只向左漂它的 0.35');
  page.data.reducedMotion = true;
  page.data.cloudOn = false;
  page._cloudDragBegin();
  assert.equal(page.data.cloudOn, false);
  assert.match(read('pages/roam/index.wxml'), /wx:if="\{\{!reducedMotion && screen=='map'\}\}" class="roam-clouds/);
});

test('free-map 接照片图钉:callout 槽里画圆照片 + 标签,点照片等同点 marker', () => {
  const wxml = read('components/cy/free-map/index.wxml');
  assert.match(wxml, /wx:for="\{\{explorePins\}\}"[^>]*marker-id="\{\{item\.id\}\}"/);
  assert.match(wxml, /bindcallouttap="onCalloutTap"/);
  assert.match(read('pages/roam/index.wxml'), /explore-pins="\{\{explorePins\}\}"/);
  const json = JSON.parse(read('pages/roam/index.json'));
  assert.equal(json.usingComponents['cy-roam-place-card'], '/subpackageRoam/components/roam-place-card/index');
  assert.equal(json.componentPlaceholder['cy-roam-place-card'], 'view', '地点卡在分包,异步加载');
});

test('照片避让:挨得太近的照片降成深色圆,选中的那枚优先', () => {
  const items = [{ id: 1, lat: 31.21, lng: 121.41, size: 40 }, { id: 2, lat: 31.21001, lng: 121.41001, size: 40 }, { id: 3, lat: 31.215, lng: 121.415, size: 40 }];
  assert.deepEqual(pins.declutter(items, REGION, { width: 390, height: 844 }).map((x) => x.id), [1, 3]);
  const page = loadRoamPage();
  page.data.center = { lat: 31.21, lng: 121.41 };
  page._mapScale = 16; page._pinRegion = REGION;
  page._pois = [poi(1, 31.21, 121.41, { photo: 'https://img/1.jpg' }), poi(2, 31.21001, 121.41001, { photo: 'https://img/2.jpg' })];
  page._pinFocusId = 2;
  page._syncMarkers();
  assert.deepEqual(page.data.explorePins.map((p) => p.id), [2]);
  assert.equal(page.data.markers.find((m) => m.id === 1).customCallout, undefined);
});

test('普通地标不是据点:地点卡不给分享、不读评分', () => {
  const page = loadRoamPage();
  page._mapCtx = { moveToLocation() {} };
  page._pois = [poi(700014, 31.21, 121.41, { _roamId: 14, isCityNode: false, photo: 'https://img/14.jpg' })];
  page.onMarkerTap({ detail: { markerId: 700014 } });
  assert.deepEqual([page.data.placeCard.shareKind, page.data.placeCard.shareId], ['', '']);
});

test('第九轮 m1 第 9 项:漫游地点卡与商家横滑卡的地址点开地图', () => {
  const wxml = read('pages/roam/index.wxml');
  assert.match(wxml, /class="pc-body cy-addr-tap"[^>]*catchtap="onAddrTap" data-lat="\{\{paceCard\.lat\}\}"/);
  // 第九轮 m2 补充第 1 项:横滑卡只剩名字、距离、动作;距离这一行点开地图
  assert.match(wxml, /class="poi__addr poi__dist cy-addr-tap"[^>]*catchtap="onAddrTap" data-lat="\{\{item\.lat\}\}"/);
  assert.doesNotMatch(wxml, /\{\{item\.meta\}\}/);
  const page = loadRoamPage();
  assert.deepEqual([page._paceCardFor({ lat: 31.2, lng: 121.4, address: 'A' }).lat, page._paceCardFor({ lat: 31.2, lng: 121.4 }).lng], [31.2, 121.4]);
  const opened = [];
  const prev = global.wx;
  global.wx = { openLocation: (o) => opened.push(o) };
  try { page.onAddrTap({ currentTarget: { dataset: { lat: 31.2, lng: 121.4, name: '店', address: '路' } } }); } finally { global.wx = prev; }
  assert.equal(opened[0].latitude, 31.2);
});

// 第九轮 m2 补充第 2 项:任意缩放下图钉互不重叠,参与商铺优先;46 枚与 200 枚都核对
function crowd(n, seed) {
  let x = seed || 7;
  const rnd = () => { x = (x * 1103515245 + 12345) % 2147483648; return x / 2147483648; };
  const out = [];
  for (let i = 0; i < n; i++) {
    const r = Math.sqrt(rnd()) * 0.01, a = rnd() * Math.PI * 2;
    out.push({ id: i, lat: 31.23 + r * Math.sin(a), lng: 121.47 + r * Math.cos(a), size: [40, 46, 52, 34, 44][i % 5],
      label: i % 7 === 0 ? 'shop' : '', photo: i % 3 !== 0, participating: i % 7 === 0, focused: false, d: r });
  }
  return out;
}
function assertNoOverlap(kept, region, vp) {
  for (let i = 0; i < kept.length; i++) for (let j = i + 1; j < kept.length; j++) {
    const a = pins.toScreen(kept[i].lat, kept[i].lng, region, vp), b = pins.toScreen(kept[j].lat, kept[j].lng, region, vp);
    const min = pins.footprint(kept[i]) + pins.footprint(kept[j]);
    assert.ok(Math.hypot(a.x - b.x, a.y - b.y) >= min, 'overlap ' + kept[i].id + '/' + kept[j].id);
  }
}
test('第九轮 m2 补充第 2 项:46 枚与 200 枚,各缩放级别都不重叠,参与商铺先放', () => {
  const vp = { width: 390, height: 844 };
  for (const n of [46, 200]) {
    const all = crowd(n, n);
    for (const scale of [15, 16, 17, 18]) {
      const region = pins.fallbackRegion({ lat: 31.23, lng: 121.47 }, scale, vp);
      const kept = pins.layoutPins(all, region, vp);
      assertNoOverlap(kept, region, vp);
      assert.ok(kept.length >= 1);
      // 放不下的参与商铺,一定是被另一枚参与商铺挡住的
      const keptIds = new Set(kept.map((k) => k.id));
      all.filter((c) => c.participating && !keptIds.has(c.id)).forEach((c) => {
        const s = pins.toScreen(c.lat, c.lng, region, vp);
        assert.ok(kept.some((k) => k.participating && (() => { const q = pins.toScreen(k.lat, k.lng, region, vp); return Math.hypot(q.x - s.x, q.y - s.y) < pins.footprint(k) + pins.footprint(c) + pins.PIN_GAP; })()));
      });
    }
    // 拉近放出更多
    const near = pins.layoutPins(all, pins.fallbackRegion({ lat: 31.23, lng: 121.47 }, 18, vp), vp).length;
    const far = pins.layoutPins(all, pins.fallbackRegion({ lat: 31.23, lng: 121.47 }, 15, vp), vp).length;
    assert.ok(near > far, n + ': near ' + near + ' far ' + far);
  }
});
test('第九轮 m2 补充第 2 项:漫游页所有非状态图钉都参与避让,放不下的降成小圆点', () => {
  const js = read('pages/roam/index.js');
  assert.match(js, /photoPins\.layoutPins\(pinCandidates, layoutRegion, this\._fogScreenSize,/);
  assert.match(js, /if \(this\._icons && this\._icons\['pin-dot'\]\) c\.m\.iconPath = this\._icons\['pin-dot'\];/);
});

test('第九轮 m2 补充第 3 项:拉远是热度——叠得越多越大越亮,位置仍取真实点位', () => {
  assert.ok(pins.heatLevel(1).size > 12, '比原来的 9px 点略大');
  assert.ok(pins.heatLevel(3).size > pins.heatLevel(1).size && pins.heatLevel(9).size > pins.heatLevel(3).size);
  assert.deepEqual([1, 3, 9].map((n) => pins.heatLevel(n).level), [1, 2, 3]);
  const js = read('pages/roam/index.js');
  assert.match(js, /this\._icons\['pin-heat' \+ lv\] = await save\(\);/);
  assert.match(js, /iconPath: icon, zIndex: 2 \+ heat\.level/);
});

test('rig 走查修正:玩家自己的点也占位,图钉不压蓝点', () => {
  const vp = { width: 390, height: 844 };
  const region = pins.fallbackRegion({ lat: 31.23, lng: 121.47 }, 17, vp);
  const c = [{ id: 1, lat: 31.23, lng: 121.47, size: 44, label: 'shop', participating: true, photo: true, d: 0 }];
  assert.strictEqual(pins.layoutPins(c, region, vp).length, 1);
  assert.strictEqual(pins.layoutPins(c, region, vp, [{ lat: 31.23, lng: 121.47, size: 48 }]).length, 0);
});
