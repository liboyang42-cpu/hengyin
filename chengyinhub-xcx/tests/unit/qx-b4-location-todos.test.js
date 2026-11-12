// qx-b4 U02-03/U02-04:作者地点待办只翻译 /binding-status;只有 level=error 挡发布,warn 只提示。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const t = require('../../pages/publish/utils/publish/location-todos.js');

const status = {
  topicId: '901', cityCode: '330100',
  sourceTemplateTopicId: '41', sourceContentRevisionId: null,
  adoption: { bindingIntent: 'NEW_CITY', targetCityCode: '330100' },
  contract: {
    locationSlots: [
      { slotKey: 'loc-71', title: '样板咖啡', observationTarget: '门口招牌', observationAction: '站在门口拍招牌', alternativePolicy: 'ALTERNATE_NODE', required: true },
      { slotKey: 'loc-72', title: '样板书店', alternativePolicy: 'NONE', required: true },
      { slotKey: 'loc-73', title: '花店', required: false }
    ],
    locationBindings: [
      { slotKey: 'loc-71', verificationStatus: 'REBIND_REQUIRED', nodeId: null, bindingRevision: 0 },
      { slotKey: 'loc-72', verificationStatus: 'BOUND_UNVERIFIED', nodeId: '9', bindingRevision: 2 },
      { slotKey: 'loc-73', verificationStatus: 'LEGACY_UNVERIFIED' }
    ]
  },
  bindingIssues: [
    { slotKey: 'loc-71', code: 'LOCATION_REBIND_REQUIRED', level: 'error', message: '跨城采用后需在目标城市重新绑定' },
    { slotKey: 'loc-73', code: 'LOCATION_BINDING_LEGACY_UNVERIFIED', level: 'warn', message: '从旧模板沿用，尚未重新确认（仅提示）' }
  ]
};

test('每个地点:看什么/怎么做/看不到时 + 状态;只有 error 计入挡发布', () => {
  const v = t.view(status);
  assert.equal(v.rows.length, 3);
  assert.deepEqual(
    { what: v.rows[0].what, how: v.rows[0].how, fallback: v.rows[0].fallback, status: v.rows[0].statusText, blocking: v.rows[0].blocking },
    { what: '门口招牌', how: '站在门口拍招牌', fallback: '可换备用地点', status: '待在新城市重绑', blocking: true });
  assert.equal(v.rows[1].bound, true);
  assert.equal(v.rows[1].revision, 2);
  assert.equal(v.rows[2].blocking, false);
  assert.equal(v.rows[2].warn, true);
  assert.equal(v.blockingCount, 1);
  assert.equal(t.summaryText(v), '1 项挡住发布');
});

test('来源修订为空显示「未锁定来源修订」,不补 1;没有合同不画', () => {
  const v = t.view(status);
  assert.deepEqual(v.source, { topicText: '来源模板 #41', revisionText: '未锁定来源修订', intentText: '换城采用' });
  assert.equal(t.view({ ...status, sourceContentRevisionId: '88' }).source.revisionText, '来源修订 #88');
  assert.equal(t.view({ contract: { locationSlots: [] } }), null);
  assert.equal(t.view(null), null);
  const warnOnly = t.view({ ...status, bindingIssues: [status.bindingIssues[1]] });
  assert.equal(t.summaryText(warnOnly), '1 项待复核');
});

test('r2-create:每个已保存节点的当前地址,供地点待办逐个「替换地点」', () => {
  assert.deepEqual(t.nodePlaces([{ nodes: [{ id: 9, name: '西湖', address: '杭州市西湖区' }, { id: 10, name: '纯线上' }, { id: 11, name: '断桥', latitude: '30.2', longitude: '120.1' }, { name: '未保存', address: 'x' }] }]),
    { 9: '杭州市西湖区', 11: '断桥' });
  const fs = require('node:fs');
  const wxml = fs.readFileSync(path.join(__dirname, '../../pages/publish/components/location-todos/index.wxml'), 'utf8');
  assert.match(wxml, /bindtap="replacePlace"/);
  assert.doesNotMatch(wxml, /view\.source|换一个节点/);
  assert.match(wxml, /bindtap="confirmPlace"/);
});

test('r2-create:同城沿用后未绑定的地点按同名节点认领;已绑定的用绑定节点;重名不认领', () => {
  const view = { rows: [{ slotKey: 'a', title: '人民广场站', nodeId: '' }, { slotKey: 'b', title: '南京东路站', nodeId: '12' }, { slotKey: 'c', title: '外滩', nodeId: '' }] };
  const chapters = [{ nodes: [
    { id: 11, name: '人民广场站', address: '黄浦区人民大道' },
    { id: 12, name: '南京东路站', address: '南京东路步行街' },
    { id: 13, name: '外滩', address: '中山东一路' }, { id: 14, name: '外滩', address: '中山东一路北' }] }];
  assert.deepEqual(t.slotNodes(view, chapters), {
    a: { id: '11', place: '黄浦区人民大道', claimed: true },
    b: { id: '12', place: '南京东路步行街', claimed: false },
  });
  assert.deepEqual(t.slotNodes(null, chapters), {});
});

test('可绑定节点只取已保存且有地点的节点', () => {
  const nodes = t.bindableNodes([{ nodes: [{ id: 9, name: '西湖', address: '杭州市西湖区' }, { name: '未保存', address: 'x' }, { id: 10, name: '纯线上' }] }]);
  assert.deepEqual(nodes, [{ id: '9', label: '西湖 · 杭州市西湖区' }]);
});

test('绑定请求带槽位与当前修订号;玩家私有字段不出现在组件模板里', () => {
  const dir = path.join(__dirname, '../../pages/publish/components/location-todos');
  const js = fs.readFileSync(path.join(dir, 'index.js'), 'utf8');
  const wxml = fs.readFileSync(path.join(dir, 'index.wxml'), 'utf8');
  assert.match(js, /\/api\/template\/topic-template\/binding-status/);
  assert.match(js, /data\.expectedRevision = String\(row\.revision\)/);
  assert.doesNotMatch(wxml, /observationNote|referenceMediaIds|confirmedBy|previousBinding/);
  assert.doesNotMatch(wxml, /showModal/);
});
