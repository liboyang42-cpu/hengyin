'use strict';

const assert = require('assert');
const test = require('node:test');

let pageConfig;
global.getApp = () => ({
  globalData: { user_id: 9, features: {} },
  isDevEnv: () => false,
  sendRequest() {},
});
global.wx = {
  getStorageSync: () => '',
  setStorageSync() {},
  getWindowInfo: () => ({ statusBarHeight: 20 }),
  getSystemInfoSync: () => ({ statusBarHeight: 20 }),
};
global.Page = (config) => { pageConfig = config; };

function loadPlayPage() {
  delete require.cache[require.resolve('../../pages/play/index.js')];
  require('../../pages/play/index.js');
  const page = Object.assign({}, pageConfig, {
    data: JSON.parse(JSON.stringify(pageConfig.data)),
  });
  page.setData = function (patch) {
    Object.keys(patch).forEach((key) => {
      const parts = key.split('.');
      let cursor = page.data;
      for (let i = 0; i < parts.length - 1; i += 1) {
        if (cursor[parts[i]] == null) cursor[parts[i]] = {};
        cursor = cursor[parts[i]];
      }
      cursor[parts[parts.length - 1]] = patch[key];
    });
    if (typeof arguments[1] === 'function') arguments[1]();
  };
  return page;
}


/* 第十二轮 k2(业主 2026-11-05「解开的站重进即算完成,玩家不用走回去」,j3 F6):
   玩法已过的站,详情卡「开始任务」/ 继续故事走到站收尾时,不再开导航、不再先要定位。 */
function solvedSetup(mode) {
  const page = loadPlayPage();
  const node = { nodeId: 7, vm: 5, lat: 31.2304, lng: 121.4737, hasAdvanced: true, done: false };
  page.data.mode = mode;
  page.data.gpsOk = false;
  page.data.sheet = { show: true, node };
  page.data.nodes = [node, { nodeId: 6, done: true }];   // 已开局(本局已有完成站):后续站才免定位
  page._advancedReadyNodeId = 7;
  const calls = [];
  page.arrive = (nodeId, via) => calls.push('arrive:' + nodeId + ':' + via);
  page.requestPlayLocation = () => calls.push('location');
  page.startNav = () => calls.push('nav');
  return { page, calls, node };
}

for (const mode of [1, 2]) {
  test('k2 解开的站(mode ' + mode + '):远处没定位也直接到站收尾,不开导航', () => {
    const { page, calls } = solvedSetup(mode);
    page.onSheetGoTap();
    assert.deepEqual(calls, ['arrive:7:arrive']);
  });
}

test('开局闸:首站(本局没有完成站)玩法已解开也要先定位,不直接收尾', () => {
  const { page, calls } = solvedSetup(1);
  page.data.nodes = [page.data.sheet.node];
  page.onSheetGoTap();
  assert.deepEqual(calls, ['location']);
});

test('k2 没解开的站仍按原样:mode 2 开导航,mode 1 先要定位', () => {
  let { page, calls } = solvedSetup(2);
  page._advancedReadyNodeId = null;
  page.onSheetGoTap();
  assert.deepEqual(calls, ['nav']);
  ({ page, calls } = solvedSetup(1));
  page._advancedReadyNodeId = null;
  page.data.nodes = [page.data.sheet.node];   // 未开局才要定位;已开局的后续站不再逐站定位
  page.onSheetGoTap();
  assert.deepEqual(calls, ['location']);
});
