const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../pages/topic/index/index.js'), 'utf8');
const start = source.indexOf('  buildRouteMap: function (chaptersList) {');
const end = source.indexOf('\n  },', start);
const helperIndex = source.indexOf('function routeCoordinate(');
const legacySpeed = source.match(/^const WALK_METERS_PER_MIN = .*;$/m);
const helpers = (legacySpeed ? legacySpeed[0] : '') + source.slice(helperIndex >= 0 ? helperIndex : source.indexOf('function walkText('), source.indexOf('/* CU-C-65:'));
const geo = require('../../utils/geo.js');
const routePreview = require('../../utils/topic-route-preview.js');
const runtime = new Function('haversine', 'distanceText', 'routePreview', helpers + '\nreturn { walk: walkText, build: function(chaptersList) {'
  + source.slice(source.indexOf('{', start) + 1, end) + '\n} };')(geo.haversine, geo.distanceText, routePreview);
const build = runtime.build;
const nodes = [{ latitude: '31', longitude: '121' }, { latitude: '31.1', longitude: '121.1' }];
const geometry = JSON.stringify(nodes);
function render(chapters) {
  const page = { data: {}, setData(value) { Object.assign(this.data, value); } };
  build.call(page, chapters);
  return page.data;
}
test('道路与直线分段，章节之间不偷偷连成道路', () => {
  const data = render([{ nodes, routeGeometryAvailable: true, routeGeometry: geometry }, { nodes }]);
  assert.equal(data.routePolyline.length, 2);
  assert.equal(data.routePolyline[0].dottedLine, false);
  assert.equal(data.routePolyline[1].dottedLine, true);
  assert.match(data.routeSourceText, /章间未规划/);
});
test('损坏或缺点的道路整段退为虚线，不跳过断点连线', () => {
  for (const routeGeometry of ['invalid', '[null]', JSON.stringify([nodes[0], null, nodes[1]])]) {
    const data = render([{ nodes, routeGeometry, routeGeometryAvailable: true }]);
    assert.match(data.routeSourceText, /节点直线预览/);
    assert.equal(data.routePolyline[0].dottedLine, true);
  }
});
test('站间坐标不换算成步行分钟', () => {
  const walk = runtime.walk;
  assert.match(walk(nodes[0], nodes[1]), /^直线约 [\d.]+ 公里$/);
  assert.equal(walk(null, nodes[1]), '');
});

const walk = runtime.walk;
test('两章道路分别成线，输入不被重排或修改', () => {
  const later = nodes.map(n => ({ ...n, latitude: Number(n.latitude) + 1, sortId: 0 }));
  const input = [{ nodes: nodes.map(n => ({ ...n, sortId: 9 })), routeGeometryAvailable: true, routeGeometry: geometry }, { nodes: later, routeGeometryAvailable: true, routeGeometry: JSON.stringify(later) }];
  const before = JSON.stringify(input), d = render(input);
  assert.equal(d.routePolyline.length, 2);
  assert.ok(d.routePolyline.every(line => line.points.length === 2 && !line.dottedLine && line.arrowLine));
  assert.equal(d.routeMarkers[0].latitude, 31);
  assert.equal(JSON.stringify(input), before);
});
const badCoordinates = [true, false, [], [31], '', '  ', null, {}, Infinity, NaN, 91, -91, 'Infinity'];
for (const value of badCoordinates) test('坏坐标不进入节点/道路或站间距离：' + String(value), () => {
  const bad = { latitude: value, longitude: 121 };
  const road = JSON.stringify([nodes[0], bad, nodes[1]]);
  const d = render([{ nodes: [nodes[0], bad, nodes[1]], routeGeometryAvailable: true, routeGeometry: road }]);
  assert.equal(d.routeMarkers.length, 2);
  assert.equal(d.routePolyline.length, 1);
  assert.equal(d.routePolyline[0].points.length, 2);
  assert.equal(d.routePolyline[0].dottedLine, true);
  assert.equal(d.routePolyline[0].arrowLine, false);
  assert.equal(walk(nodes[0], bad), '');
});
test('经度也拒绝类型/空/越界；非法对象不执行强转钩子', () => {
  const hostile = { valueOf() { throw Error('不应强转'); } };
  for (const longitude of [hostile, Symbol('bad'), true, [], '', ' ', 181, -181]) {
    const bad = { latitude: 31, longitude };
    assert.equal(render([{ nodes: [bad] }]).routeReady, false);
    assert.equal(walk(nodes[0], bad), '');
  }
});
test('数字字符串、单轴零与范围边界合法，lat/lng道路别名兼容', () => {
  const d = render([{ nodes: [{ latitude: 0, longitude: 120 }, { latitude: 1, longitude: 0 }],
    routeGeometryAvailable: true, routeGeometry: JSON.stringify([{ lat: '-90', lng: '-180' }, { lat: '90', lng: '180' }]) }]);
  assert.equal(d.routeMarkers.length, 2);
  assert.equal(d.routePolyline[0].dottedLine, false);
  assert.deepEqual(d.routePolyline[0].points, [{ latitude: -90, longitude: -180 }, { latitude: 90, longitude: 180 }]);
  assert.match(walk({ latitude: 0, longitude: 120 }, { latitude: 0, longitude: 121 }), /^直线约 /);
});
test('无道路只连本章合法节点，不能跨章凑够两个点；全部marker都在视野里', () => {
  const d = render([{ nodes, routeGeometryAvailable: true, routeGeometry: geometry }, { nodes: [{ latitude: 20, longitude: 100 }] }]);
  assert.equal(d.routePolyline.length, 1);
  assert.ok(d.routeIncludePoints.some(p => p.latitude === 20 && p.longitude === 100));
  assert.equal(render([{ nodes: [nodes[0]] }, { nodes: [nodes[1]] }]).routePolyline.length, 0);
});
test('损坏/不足道路不产生箭头；空结果清空旧地图，合法道路可独立显示', () => {
  for (const routeGeometry of ['{}', 'null', '[]', JSON.stringify([nodes[0]])]) {
    const d = render([{ nodes, routeGeometry, routeGeometryAvailable: true }]);
    assert.equal(d.routePolyline[0].dottedLine, true); assert.equal(d.routePolyline[0].arrowLine, false);
  }
  const page = { data: render([{ nodes, routeGeometryAvailable: true, routeGeometry: geometry }]), setData(patch) { Object.assign(this.data, patch); } };
  build.call(page, null);
  assert.equal(page.data.routeReady, false);
  assert.equal(page.data.mapLatitude, 0); assert.equal(page.data.mapLongitude, 0);
  assert.deepEqual(page.data.routePolyline, []); assert.deepEqual(page.data.routeMarkers, []); assert.deepEqual(page.data.routeIncludePoints, []);
  assert.equal(render([{ nodes: [], routeGeometryAvailable: true, routeGeometry: geometry }]).routeReady, true);
});
test('模板统一显示真实来源，距离文案不承诺分钟，重复坐标不编时长', () => {
  const wxml = fs.readFileSync(path.join(__dirname, '../../pages/topic/index/index.wxml'), 'utf8');
  assert.match(wxml, /class="route-map-tip"[^>]*>\{\{routeSourceText\}\}/);
  assert.doesNotMatch(wxml, /route-map-tip[^>]*wx:if="\{\{!routeIsRoad\}\}"/);
  assert.doesNotMatch(walk(nodes[0], nodes[1]), /\d+\s*(?:min|分钟)/);
  assert.equal(walk(nodes[0], nodes[0]), '');
});

test('真实详情请求→章节整形→地图投影保留分章来源与直线距离', () => {
  const previous = { getApp: global.getApp, wx: global.wx, Page: global.Page };
  const requests = []; let definition;
  const app = { globalData: {}, getUserID: () => 1, sendRequest(options) { requests.push(options); } };
  global.getApp = () => app;
  global.wx = new Proxy({}, { get: () => () => {} });
  global.Page = page => { definition = page; };
  const entry = require.resolve('../../pages/topic/index/index.js');
  try {
    delete require.cache[entry]; require(entry);
    const page = { ...definition, data: { ...JSON.parse(JSON.stringify(definition.data)), id: 91 },
      setData(patch) { Object.assign(this.data, patch); }, getTabSectionPosition() {}, getTabHeight() {} };
    page.getData(); assert.equal(requests.length, 1); assert.equal(requests[0].url, '/api/topic/info-to-user');
    requests[0].success({ code: 200, data: { id: 91, name: '章节路线', productType: 1, omsTicketList: [], totalTime: 60,
      chaptersList: [{ name: '第一章', nodes: JSON.parse(JSON.stringify(nodes)), routeGeometryAvailable: true, routeGeometry: geometry },
        { name: '第二章', nodes: JSON.parse(JSON.stringify(nodes)) }] } });
    assert.equal(page.data.topicLoaded, true); assert.equal(page.data.routePolyline.length, 2);
    assert.equal(page.data.routePolyline[0].dottedLine, false); assert.equal(page.data.routePolyline[1].dottedLine, true);
    assert.match(page.data.routeSourceText, /道路.*节点直线.*章间未规划/);
    const chapters = page.data.info.chaptersList;
    // 2026-10-23 v13:站间步行线跨章接上(原型每章标题下也有一段),仍只写直线距离
    assert.match(chapters[1].nodes[0].walkText, /^直线约 /); assert.equal(chapters[0].nodes[0].walkText, ''); assert.match(chapters[0].nodes[1].walkText, /^直线约 /);
    assert.doesNotMatch(chapters[0].nodes[1].walkText, /\d+\s*(?:min|分钟)/);
  } finally {
    global.getApp = previous.getApp; global.wx = previous.wx; global.Page = previous.Page; delete require.cache[entry];
  }
});

test('数组道路中的空洞也属于断点，不能当作完整道路', () => {
  const sparse = [nodes[0]]; sparse.length = 2; sparse.push(nodes[1]);
  const data = render([{ nodes, routeGeometryAvailable: true, routeGeometry: sparse }]);
  assert.equal(data.routePolyline[0].dottedLine, true); assert.equal(data.routePolyline[0].arrowLine, false);
});
