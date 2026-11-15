'use strict';

const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const PAGE = '../../pages/publish/fabu/index.js';
const clone = value => JSON.parse(JSON.stringify(value));
const coords = { longitude: 121.475, latitude: 31.23 };
let config, picker, toasts, requests;

beforeEach(t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  picker = null; toasts = []; requests = [];
  global.getCurrentPages = () => [];
  global.getApp = () => ({
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    getUserID: () => 42,
    getRequestErrorMessage: (_, fallback) => fallback,
    sendRequest: request => requests.push(request),
  });
  global.wx = {
    getStorageSync: () => null, setStorageSync() {}, removeStorageSync() {},
    showToast: options => toasts.push(options.title),
    chooseLocation: options => { picker = options; },
    getLocation: () => assert.fail('选点不得改为自动定位'),
    showModal: options => options.success({ confirm: false }),
    getSystemInfoSync: () => ({ windowWidth: 375 }),
    pageScrollTo() {}, nextTick: callback => callback(),
  };
  global.Page = value => { config = value; };
  delete require.cache[require.resolve(PAGE)];
  require(PAGE);
});

function page() {
  const p = Object.assign({}, config, { data: clone(config.data) });
  p.setData = (patch, done) => {
    for (const [key, value] of Object.entries(patch)) {
      const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.');
      let target = p.data;
      for (const part of parts.slice(0, -1)) target = target[part] ||= {};
      target[parts.at(-1)] = value;
    }
    if (done) done();
  };
  p.data.nodesForm = Object.assign(p._emptyNodesForm(), {
    _localId: 'node', name: '六分钟线索', description: '保留作者文字',
    templateId: 7, templateInfo: { id: 7, title: '已配玩法', advancedConfigJson: '{}' },
  });
  p.data.popChapterNodes = true;
  p.data.mapScale = 12;
  p._installDraftAutosave();
  return p;
}

for (const address of [undefined, '']) {
  test('抽屉使用实际pickLocation：地址' + (address === undefined ? '缺省' : '为空') + '时保留选中地点名', () => {
    const p = page();
    const before = clone(p.data.nodesForm);
    p.choosePoiForNode();
    picker.success({ name: '上海人民广场', address, ...coords });
    assert.equal(p.data.nodesForm.address, '上海人民广场');
    assert.equal(p.data.nodesForm.name, before.name);
    assert.deepEqual(p.data.nodesForm.templateInfo, before.templateInfo);
    assert.equal(p.data.nodesForm.description, before.description);
    assert.equal(p.data.nodesForm.longitude, coords.longitude);
    assert.equal(p.data.nodesForm.latitude, coords.latitude);
    assert.equal(p.data.nodesFormLocationReady, true);
    assert.ok(p.data.mapMarkers.some(marker => marker.id === 900001));
    p.buildRouteMap();
    assert.equal(p.data.nodesForm.address, '上海人民广场', '真实地图重建不能改掉表单地点');
    assert.ok(toasts.includes('地点已定位到地图'));
    assert.equal(requests.length, 0);
  });
}

test('真实地址优先，重选只改地点，不把地点名覆盖作者节点名与玩法', () => {
  const p = page();
  const info = clone(p.data.nodesForm.templateInfo);
  Object.assign(p.data.nodesForm, { address: '旧地址', longitude: 121.4, latitude: 31.2 });
  p.choosePoiForNode();
  assert.equal(picker.longitude, 121.4);
  assert.equal(picker.latitude, 31.2);
  picker.success({ name: '上海人民广场', address: '选点返回的具体地址', ...coords });
  assert.equal(p.data.nodesForm.address, '选点返回的具体地址');
  assert.equal(p.data.nodesForm.name, '六分钟线索');
  assert.deepEqual(p.data.nodesForm.templateInfo, info);
  assert.equal(p.data.nodesForm.templateId, 7);
});

test('原节点名为空才使用选中地点名，地点字段同时使用同一真实名称', () => {
  const p = page(); p.data.nodesForm.name = '';
  p.choosePoiForNode(); picker.success({ name: '上海人民广场', ...coords });
  assert.equal(p.data.nodesForm.name, '上海人民广场');
  assert.equal(p.data.nodesForm.address, '上海人民广场');
});

test('两种文本均缺失时保持空地点，不从节点名或坐标制造地址', () => {
  const p = page();
  p.choosePoiForNode(); picker.success({ ...coords });
  assert.equal(p.data.nodesForm.address, '');
  assert.equal(p.data.nodesForm.name, '六分钟线索');
  assert.equal(p.data.nodesForm.longitude, coords.longitude);
});

test('无效坐标仍由原helper拒绝，不落地点文本或成功提示', () => {
  const p = page(); const before = clone(p.data.nodesForm);
  p.choosePoiForNode(); picker.success({ name: '上海人民广场', address: '某地址', longitude: 0, latitude: 0 });
  assert.deepEqual(p.data.nodesForm, before);
  assert.ok(!toasts.includes('地点已定位到地图'));
  assert.ok(toasts.includes('没找到该地点坐标，请换个词重选'));
});

test('正常取消不改表单且静默，不自动定位或提交', () => {
  const p = page(); const before = clone(p.data.nodesForm);
  p.choosePoiForNode(); picker.fail({ errMsg: 'chooseLocation:fail cancel' });
  assert.deepEqual(p.data.nodesForm, before);
  assert.deepEqual(toasts, []);
  assert.deepEqual(requests, []);
});

test('拒绝位置权限不改表单，沿用原权限恢复提示', () => {
  const p = page(); const before = clone(p.data.nodesForm);
  p.choosePoiForNode(); picker.fail({ errMsg: 'chooseLocation:fail auth deny' });
  assert.deepEqual(p.data.nodesForm, before);
  assert.ok(toasts.includes('需要位置权限，请在设置中开启'));
  assert.equal(requests.length, 0);
});

test('抽屉选择→真实节点确认→故事卡投影→真实云草稿payload保持地点文本与玩法引用', () => {
  const p = page();
  p.data.formData.productType = 1;
  p.data.formData.chapters = [{ _localId: 'chapter', name: '第1章', nodes: [clone(p.data.nodesForm)], blocks: [
    { type: 'text', key: 'text', content: '先看这段真实剧情再到地点' },
    { type: 'node', key: 'block', nodeKey: 'node' },
  ] }];
  Object.assign(p.data, { popChapterNodesAction: 1, editTargetChapterLid: 'chapter', editTargetNodeLid: 'node' });
  p.choosePoiForNode(); picker.success({ name: '上海人民广场', address: '', ...coords });
  p.confrimNode();
  const chapter = p.data.formData.chapters[0], node = chapter.nodes[0];
  assert.equal(node.address, '上海人民广场');
  assert.equal(chapter.blocks.find(block => block.type === 'node')._nodeAddress, '上海人民广场');
  assert.equal(p.data.popChapterNodes, false);
  const payload = p._buildSubmitPayload({ draft: true, quiet: true });
  assert.ok(payload);
  const sentNode = payload.chapters[0].nodes[0];
  assert.equal(sentNode.address, '上海人民广场');
  assert.equal(Number(sentNode.longitude), coords.longitude);
  assert.equal(Number(sentNode.latitude), coords.latitude);
  assert.equal(sentNode.templateId, 7);
  assert.equal(sentNode.name, '六分钟线索');
  assert.equal(requests.length, 0, '只检验生产payload组装，不发业务写请求');
});

test('既有inline选址入口与抽屉在name-only地点上保持相同文本合同', () => {
  const p = page();
  p.data.formData.productType = 2;
  p.data.formData.chapters = [{ _localId: 'chapter', name: '第1章', nodes: [clone(p.data.nodesForm)] }];
  p.pickNodeLocation({ currentTarget: { dataset: { chapterindex: 0, nodeindex: 0 } } });
  picker.success({ name: '上海人民广场', ...coords });
  assert.equal(p.data.formData.chapters[0].nodes[0].address, '上海人民广场');
  assert.equal(p.data.nodesForm.address, '上海人民广场');
});
