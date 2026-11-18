'use strict';
// FE-STORY 7.10:自由探索每个地点的「到访方式」(只给主题作者)。
// 请求形状:GET /api/free-explore/visit-config?topicId=X;POST 同一路径,JSON { nodeId, visitCheck, visitRadius }。
// 半径只能 300 / 400 / 500 米(null = 默认 300);错误写在字段下面;保存结果不明只回读,不自动重发。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.join(__dirname, '../..');
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');

let requests = [];
global.getApp = () => ({ sendRequest: (r) => { requests.push(r); } });
const cfg = require('../../pages/publish/utils/publish/fe-visit-config.js');

const ROWS = [
  { nodeId: 7, name: '示例站点', chapterId: 3, visitCheck: 'GPS', visitRadius: null, hasCoordinates: true },
  { nodeId: 8, name: '另一处', chapterId: 3, visitCheck: 'SCAN', visitRadius: null, hasCoordinates: false },
];

function harness(extra) {
  const page = Object.assign({}, cfg.methods(), {
    data: Object.assign({ formData: { productType: 2 }, editingTopicId: '42', feVisit: Object.assign({}, cfg.data.feVisit) }, extra),
    setData(patch) {
      Object.keys(patch).forEach((k) => {
        if (k.indexOf('.') > 0) { const [a, b] = k.split('.'); this.data[a] = Object.assign({}, this.data[a], { [b]: patch[k] }); }
        else this.data[k] = patch[k];
      });
    },
  });
  return page;
}
function open(page, nodeId) {
  requests = [];
  page._openFeVisitConfig({ id: nodeId });
  assert.equal(requests.length, 1);
  requests[0].success({ code: 200, data: ROWS });
  requests = [];
}

test('请求形状:GET 带 topicId;POST 是 JSON { nodeId, visitCheck, visitRadius },非 GPS 半径为 null', () => {
  assert.deepEqual(cfg.loadRequest('42'), { url: '/api/free-explore/visit-config', method: 'GET', data: { topicId: 42 } });
  const gps = cfg.saveRequest('7', 'GPS', 400);
  assert.equal(gps.url, '/api/free-explore/visit-config');
  assert.equal(gps.method, 'POST');
  assert.equal(gps.header['Content-Type'], 'application/json');
  assert.deepEqual(JSON.parse(gps.data), { nodeId: 7, visitCheck: 'GPS', visitRadius: 400 });
  assert.deepEqual(JSON.parse(cfg.saveRequest(7, 'SCAN', 400).data), { nodeId: 7, visitCheck: 'SCAN', visitRadius: null });
  assert.deepEqual(JSON.parse(cfg.saveRequest(7, 'ONLINE').data), { nodeId: 7, visitCheck: 'ONLINE', visitRadius: null });
  assert.deepEqual(cfg.CHECKS.map((c) => c.label), ['GPS（默认）', '扫码', '仅线上']);
});

test('半径上下限:只认 300 / 400 / 500;null 显示为默认 300', () => {
  assert.equal(cfg.shownRadius(null), 300);
  assert.equal(cfg.shownRadius(400), 400);
  assert.equal(cfg.shownRadius(650), 300);
  [300, 400, 500].forEach((n) => assert.equal(cfg.radiusValid(n), true));
  [299, 350, 501, 600, 200, NaN].forEach((n) => assert.equal(cfg.radiusValid(n), false, String(n)));
  assert.equal(cfg.precheck({ hasCoordinates: true }, 'GPS', 600), cfg.ERRORS.VISIT_RADIUS_INVALID);
  assert.equal(cfg.precheck({ hasCoordinates: false }, 'GPS', 300), '这里还没有坐标，请先选位置或改为扫码');
  assert.equal(cfg.precheck({ hasCoordinates: false }, 'SCAN', null), '');
  assert.equal(cfg.precheck({}, 'WALK', null), cfg.ERRORS.VISIT_CHECK_INVALID);
  const wxml = read('pages/publish/fabu/index.wxml');
  assert.match(wxml, /<cy-stepper label="到访范围" value="\{\{feVisit\.radius\}\}" min="\{\{feVisitRadiusMin\}\}" max="\{\{feVisitRadiusMax\}\}" step="\{\{feVisitRadiusStep\}\}"/);
  assert.deepEqual([cfg.data.feVisitRadiusMin, cfg.data.feVisitRadiusMax, cfg.data.feVisitRadiusStep], [300, 500, 100]);
});

test('错误映射:三个 errorCode 与 403 各有一句,写在字段下面', () => {
  assert.equal(cfg.errorText({ code: 500, errorCode: 'VISIT_RADIUS_INVALID' }), cfg.ERRORS.VISIT_RADIUS_INVALID);
  assert.equal(cfg.errorText({ code: 500, errorCode: 'VISIT_GPS_COORD_REQUIRED', msg: 'x' }), '这里还没有坐标，请先选位置或改为扫码');
  assert.equal(cfg.errorText({ code: 500, errorCode: 'VISIT_CHECK_INVALID' }), cfg.ERRORS.VISIT_CHECK_INVALID);
  assert.equal(cfg.errorText({ code: 403, msg: 'forbidden' }), cfg.ERRORS.FORBIDDEN);
  assert.equal(cfg.errorText({ msg: 'forbidden' }, 403), cfg.ERRORS.FORBIDDEN);
  assert.equal(cfg.errorText({ code: 500, msg: '主题不是自由探索' }), '主题不是自由探索');
  assert.equal(cfg.errorText({ code: 500 }), cfg.ERRORS.FAILED);
  const wxml = read('pages/publish/fabu/index.wxml');
  assert.match(wxml, /<cy-inline-error class="node-binding__error" wx:if="\{\{feVisit\.error\}\}" kind="error" title="\{\{feVisit\.error\}\}" \/>/);
});

test('打开:只有自由探索、已保存的主题、已入库的地点才读;读到后显示本地点的方式与半径', () => {
  const city = harness({ formData: { productType: 1 } });
  requests = [];
  city._openFeVisitConfig({ id: 7 });
  assert.equal(requests.length, 0);
  assert.equal(city.data.feVisit.show, false);
  const draft = harness({ editingTopicId: '' });
  draft._openFeVisitConfig({ id: 7 });
  assert.equal(requests.length, 0);
  const fresh = harness();
  fresh._openFeVisitConfig({ _localId: 'l1' });
  assert.equal(requests.length, 0, '还没入库的地点没有 nodeId');

  const page = harness();
  page._openFeVisitConfig({ id: 8 });
  assert.equal(requests[0].url, '/api/free-explore/visit-config');
  assert.equal(requests[0].method, 'GET');
  assert.deepEqual(requests[0].data, { topicId: 42 });
  assert.equal(page.data.feVisit.loading, true);
  requests[0].success({ code: 200, data: ROWS });
  assert.deepEqual([page.data.feVisit.show, page.data.feVisit.check, page.data.feVisit.radius, page.data.feVisit.loading], [true, 'SCAN', 300, false]);

  const denied = harness();
  requests = [];
  denied._openFeVisitConfig({ id: 7 });
  requests[0].successStatusAbnormal({ msg: 'no' }, 403);
  assert.equal(denied.data.feVisit.error, cfg.ERRORS.FORBIDDEN);
});

test('点选即存:一次点击一条 POST;成功落服务端回包;被拒回退并把错误写在字段下', () => {
  const page = harness();
  open(page, 7);
  page.onFeVisitRadius({ detail: { value: 400 } });
  assert.equal(requests.length, 1);
  assert.deepEqual(JSON.parse(requests[0].data), { nodeId: 7, visitCheck: 'GPS', visitRadius: 400 });
  assert.equal(page.data.feVisit.saving, true);
  page.onFeVisitRadius({ detail: { value: 500 } });
  assert.equal(requests.length, 1, '保存中再点不发');
  requests[0].success({ code: 200, data: { nodeId: 7, visitCheck: 'GPS', visitRadius: 400, hasCoordinates: true } });
  assert.deepEqual([page.data.feVisit.check, page.data.feVisit.radius, page.data.feVisit.saving, page.data.feVisit.error], ['GPS', 400, false, '']);

  requests = [];
  page.onFeVisitCheck({ currentTarget: { dataset: { check: 'ONLINE' } } });
  assert.deepEqual(JSON.parse(requests[0].data), { nodeId: 7, visitCheck: 'ONLINE', visitRadius: null });
  requests[0].success({ code: 500, errorCode: 'VISIT_CHECK_INVALID', msg: 'bad' });
  assert.equal(page.data.feVisit.check, 'GPS', '被拒回到原来的选择');
  assert.equal(page.data.feVisit.radius, 400);
  assert.equal(page.data.feVisit.error, cfg.ERRORS.VISIT_CHECK_INVALID);
});

test('没坐标的地点选 GPS:本地就拦,不发请求;服务端回 VISIT_GPS_COORD_REQUIRED 同样写在字段下', () => {
  const page = harness();
  open(page, 8);
  page.onFeVisitCheck({ currentTarget: { dataset: { check: 'GPS' } } });
  assert.equal(requests.length, 0);
  assert.equal(page.data.feVisit.error, '这里还没有坐标，请先选位置或改为扫码');
  assert.equal(page.data.feVisit.check, 'SCAN');

  const other = harness();
  open(other, 7);
  other._feVisitRows['7'].hasCoordinates = undefined;   // GET 之后作者删了坐标:由服务端判
  other.onFeVisitCheck({ currentTarget: { dataset: { check: 'SCAN' } } });
  requests[0].success({ code: 200, data: { nodeId: 7, visitCheck: 'SCAN', visitRadius: null } });
  requests = [];
  other.onFeVisitCheck({ currentTarget: { dataset: { check: 'GPS' } } });
  requests[0].success({ code: 500, errorCode: 'VISIT_GPS_COORD_REQUIRED', msg: '这里还没有坐标，请先选位置或改为扫码' });
  assert.equal(other.data.feVisit.check, 'SCAN');
  assert.equal(other.data.feVisit.error, '这里还没有坐标，请先选位置或改为扫码');
});

test('网络失败 / 5xx:不自动重发,只提示并回读 GET 看服务端实际存了什么', () => {
  const page = harness();
  open(page, 7);
  page.onFeVisitCheck({ currentTarget: { dataset: { check: 'SCAN' } } });
  assert.equal(requests.length, 1);
  requests[0].fail({ errMsg: 'request:fail' });
  assert.equal(page.data.feVisit.error, cfg.ERRORS.NETWORK);
  assert.equal(requests.length, 2);
  assert.equal(requests[1].method, 'GET', '只回读');
  assert.equal(requests.filter((r) => r.method === 'POST').length, 1, '没有重发写');
  requests[1].success({ code: 200, data: [Object.assign({}, ROWS[0], { visitCheck: 'SCAN' })] });
  assert.equal(page.data.feVisit.check, 'SCAN', '以服务端为准');
  assert.equal(page.data.feVisit.error, cfg.ERRORS.NETWORK, '提示留着');

  requests = [];
  page.onFeVisitCheck({ currentTarget: { dataset: { check: 'ONLINE' } } });
  requests[0].successStatusAbnormal({ msg: 'gateway' }, 502);
  assert.equal(requests.filter((r) => r.method === 'POST').length, 1);
  assert.equal(requests[1].method, 'GET');
});

test('编辑器接线:节点抽屉里只给自由探索地点出「到访方式」;开抽屉读、关抽屉清', () => {
  const js = read('pages/publish/fabu/index.js');
  assert.match(js, /\.\.\.feVisitConfig\.methods\(\)/);
  assert.match(js, /\.\.\.feVisitConfig\.data/);
  assert.equal((js.match(/this\._openFeVisitConfig\(node\);/g) || []).length, 2, '列表点开与地图点开两处');
  assert.match(js, /this\._closeFeVisitConfig\(\);\n    this\.setData\(\{\n      popChapterNodes: false,/);
  const wxml = read('pages/publish/fabu/index.wxml');
  assert.match(wxml, /wx:if="\{\{formData\.productType === 2 && popChapterNodesAction==1 && feVisit\.show && nodesForm\.id == feVisit\.nodeId\}\}"/);
  assert.match(wxml, /<text class="node-card__title">到访方式<\/text>/);
  assert.match(wxml, /data-check="\{\{item\.key\}\}" bindtap="onFeVisitCheck"/);
  assert.match(wxml, /<view class="node-card__row fe-visit__radius" wx:if="\{\{feVisit\.check === 'GPS'\}\}">/);
});
