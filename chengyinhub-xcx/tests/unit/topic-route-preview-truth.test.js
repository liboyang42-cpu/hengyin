const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../pages/topic/index/index.js'), 'utf8');
const start = source.indexOf('  buildRouteMap: function (chaptersList) {');
const end = source.indexOf('\n  },', start);
const build = new Function('chaptersList', source.slice(source.indexOf('{', start) + 1, end));
const nodes = [{ latitude: '31', longitude: '121' }, { latitude: '31.1', longitude: '121.1' }];
const geometry = JSON.stringify(nodes);
function render(chapters) {
  const page = { data: {}, setData(value) { Object.assign(this.data, value); } };
  build.call(page, chapters);
  return page.data;
}
test('道路与直线分段，章节之间不偷偷连成道路', () => {
  const data = render([{ nodes, routeGeometry: geometry }, { nodes }]);
  assert.equal(data.routePolyline.length, 2);
  assert.equal(data.routePolyline[0].dottedLine, false);
  assert.equal(data.routePolyline[1].dottedLine, true);
  assert.match(data.routeSourceText, /章间未规划/);
});
test('损坏或缺点的道路整段退为虚线，不跳过断点连线', () => {
  for (const routeGeometry of ['invalid', '[null]', JSON.stringify([nodes[0], null, nodes[1]])]) {
    const data = render([{ nodes, routeGeometry }]);
    assert.match(data.routeSourceText, /节点直线预览/);
    assert.equal(data.routePolyline[0].dottedLine, true);
  }
});
test('站间坐标不换算成步行分钟', () => {
  const a = source.indexOf('function walkText(');
  const b = source.indexOf('\n}', a) + 2;
  const geo = require('../../utils/geo.js');
  const walk = new Function('haversine', 'distanceText', 'return (' + source.slice(a, b) + ')')(geo.haversine, geo.distanceText);
  assert.match(walk(nodes[0], nodes[1]), /^直线约 .*步行用时待确认$/);
  assert.equal(walk(null, nodes[1]), '');
});
