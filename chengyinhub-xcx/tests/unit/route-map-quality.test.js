const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const preview = require('../../utils/topic-route-preview.js');
const source = fs.readFileSync(path.join(__dirname, '../../pages/square/components/route-map/index.js'), 'utf8');
const nodes = [{ latitude: 31, longitude: 121 }, { latitude: 31.1, longitude: 121.1 }];
const geometry = JSON.stringify([{ latitude: 31, longitude: 121 }, { latitude: 45, longitude: 130 }]);

function component() {
  let definition, revision = 1;
  const requests = [], events = [], session = { getRevision: () => revision };
  const app = { getSession: () => session, sendRequest: request => requests.push(request) };
  vm.runInNewContext(source, {
    Component(value) { definition = value; }, getApp: () => app,
    require(name) { return name.includes('topic-route-preview') ? preview : () => {}; }
  });
  const host = { ...definition.methods, data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, done) { Object.assign(this.data, patch); if (done) done(); },
    triggerEvent(name) { events.push(name); } };
  return { host, requests, events, definition, switchSession() { revision++; } };
}

test('缺少/非布尔flag、明确不可用或有error时不能把旧几何当道路', () => {
  for (const metadata of [{}, { routeGeometryAvailable: 'true' }, { routeGeometryAvailable: false },
    { routeGeometryAvailable: true, routeGeometryErrorCode: 'MAP_ROUTE_UNAVAILABLE' }]) {
    const view = preview.build([{ nodes, routeGeometry: geometry, ...metadata }]);
    assert.equal(view.routeIsRoad, false);
    assert.equal(view.routePolyline[0].dottedLine, true);
    assert.equal(view.routePolyline[0].arrowLine, false);
    assert.deepEqual(view.routePolyline[0].points, nodes);
    assert.match(view.routeSourceText, /节点直线预览/);
  }
});

test('广场真实请求回调复用分章投影，混合结果不能跨章连线或冒充全部道路', () => {
  const { host, requests, events } = component();
  host.loadRoute(11);
  assert.equal(requests[0].url, '/api/topic/info-to-user');
  assert.equal(requests[0].data.id, 11);
  const later = nodes.map(p => ({ latitude: p.latitude + 1, longitude: p.longitude + 1 }));
  const chapters = [{ nodes, routeGeometry: geometry, routeGeometryAvailable: true },
    { nodes: later, routeGeometry: null, routeGeometryAvailable: false, routeGeometryErrorCode: 'MAP_ROUTE_UNAVAILABLE' }];
  const original = JSON.stringify(chapters);
  requests[0].success({ code: 200, data: { id: 11, chaptersList: chapters } });
  assert.equal(host.data.routePolyline.length, 2);
  assert.equal(host.data.routePolyline[0].dottedLine, false);
  assert.equal(host.data.routePolyline[1].dottedLine, true);
  assert.equal(host.data.routePolyline[1].arrowLine, false);
  assert.deepEqual(host.data.routePolyline[1].points, later);
  assert.equal(host.data.routeIsRoad, false);
  assert.match(host.data.routeSourceText, /道路.*节点直线.*章间未规划/);
  assert.equal(JSON.stringify(chapters), original);
  assert.deepEqual(events, ['ready']);
  const template = fs.readFileSync(path.join(__dirname, '../../pages/square/components/route-map/index.wxml'), 'utf8');
  assert.match(template, /class="rm-tip">\{\{routeSourceText\}\}/);
});

test('旧主题/旧会话/已卸载组件的回包均不能改当前地图，失败清空旧值', () => {
  const { host, requests, definition, switchSession } = component();
  const result = id => ({ code: 200, data: { id, chaptersList: [{ nodes, routeGeometry: geometry, routeGeometryAvailable: true }] } });
  host.loadRoute(11); host.loadRoute(12);
  requests[0].success(result(11));
  assert.equal(host.data.routeReady, false);
  requests[1].success(result(12));
  assert.equal(host.data.routeReady, true);
  host.loadRoute(13); switchSession(); requests[2].success(result(13));
  assert.equal(host.data.routeReady, false);
  host.loadRoute(14); definition.lifetimes.detached.call(host); requests[3].success(result(14));
  assert.equal(host.data.routeReady, false);
  assert.deepEqual(host.data.routePolyline, []);
  const next = component(); next.host.loadRoute(15); next.requests[0].success(result(15));
  assert.equal(next.host.data.routeReady, true);
  next.host.loadRoute(16); next.requests[1].fail();
  assert.equal(next.host.data.routeReady, false);
  assert.deepEqual(next.host.data.routePolyline, []);
  next.host.loadRoute(17); next.requests[2].success(result(18));
  assert.equal(next.host.data.routeReady, false, 'the body must belong to the requested topic');
});

test('坏供应商坐标不能在广场投影中跳点拼成实线路线', () => {
  const { host } = component();
  host.buildRouteMap([{ nodes, routeGeometryAvailable: true,
    routeGeometry: JSON.stringify([nodes[0], null, nodes[1]]) }]);
  assert.equal(host.data.routePolyline.length, 1);
  assert.equal(host.data.routePolyline[0].dottedLine, true);
  assert.equal(host.data.routePolyline[0].arrowLine, false);
  assert.match(host.data.routeSourceText, /节点直线预览/);
});
