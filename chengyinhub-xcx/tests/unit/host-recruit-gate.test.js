process.env.TZ = 'UTC';

const { beforeEach, test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

/**
 * 2026-10-31 业主:俱乐部 / 商家招商在发布之后做,发布时的开关决定工作台能用什么,关着的那颗灰掉点不了;
 * 商家发的城市定向只能自己带(D3 2026-09-05),两颗都灰。判据来自 /api/project/home 的
 * host.openClubPool / host.recruitChapterCount 与页面的 operationScope。
 */
const PAGE = '../../pages/topic/merchantinfo/merchantinfo.js';
const HOST_WXML = path.resolve(__dirname, '../../pages/topic/components/project-host/index.wxml');
const HOST_JS = path.resolve(__dirname, '../../pages/topic/components/project-host/index.js');
const PAGE_WXML = path.resolve(__dirname, '../../pages/topic/merchantinfo/merchantinfo.wxml');

let pageConfig;

global.getApp = () => ({
  globalData: { statusBarHeight: 20, navBarHeight: 44 },
  getUserID: () => 1,
  sendRequest: () => {},
  tips: () => {},
});
global.wx = {
  navigateTo: () => {}, reLaunch: () => {}, showToast: () => {}, showLoading: () => {},
  hideLoading: () => {}, navigateBack: () => {}, openLocation: () => {}, pageScrollTo: () => {},
  showModal: () => {},
};
global.Page = (config) => { pageConfig = config; };

beforeEach(() => {
  pageConfig = null;
  delete require.cache[require.resolve(PAGE)];
  require(PAGE);
});

function makePage() {
  const page = Object.assign({}, pageConfig);
  page.data = JSON.parse(JSON.stringify(pageConfig.data));
  page.setData = (patch) => {
    Object.keys(patch).forEach((k) => {
      if (k.indexOf('.') < 0) { page.data[k] = patch[k]; return; }
      const parts = k.split('.');
      let cur = page.data;
      for (let i = 0; i < parts.length - 1; i++) {
        if (cur[parts[i]] == null || typeof cur[parts[i]] !== 'object') cur[parts[i]] = {};
        cur = cur[parts[i]];
      }
      cur[parts[parts.length - 1]] = patch[k];
    });
  };
  return page;
}

const keys = (list) => list.map((item) => item.key + (item.disabled ? ':off' : ''));

test('商家发的城市定向:「俱乐部」「商家」两颗都灰,原因写清', () => {
  const page = makePage();
  page.data.operationScope = 'MERCHANT';
  const gate = page.hostRecruitGate({ productType: 1 }, { openClubPool: true, recruitChapterCount: 3 });
  const list = page.buildQuickActions('host', 'merchant', true, gate);
  assert.deepEqual(keys(list).slice(0, 2), ['club:off', 'merchant:off']);
  assert.match(list[0].disabledReason, /只能自己带/);
});

test('城市定向没开公开承接池:只灰「俱乐部」;自由探索一站没开商家承接:只灰「商家」', () => {
  const page = makePage();
  let list = page.buildQuickActions('host', 'merchant', true, page.hostRecruitGate({ productType: 1 }, { openClubPool: false }));
  assert.deepEqual(keys(list).slice(0, 2), ['club:off', 'merchant']);
  list = page.buildQuickActions('host', 'merchant', true, page.hostRecruitGate({ productType: 2 }, { openClubPool: false, recruitChapterCount: 0 }));
  assert.deepEqual(keys(list).slice(0, 2), ['club', 'merchant:off'], '自由探索的俱乐部承办是开售前必办,不灰');
});

test('后端没下发判据(老接口)时不灰;俱乐部主办仍然没有「俱乐部」格', () => {
  const page = makePage();
  let list = page.buildQuickActions('host', 'merchant', true, page.hostRecruitGate({ productType: 2 }, {}));
  assert.deepEqual(keys(list).slice(0, 2), ['club', 'merchant']);
  list = page.buildQuickActions('host', 'club', true, page.hostRecruitGate({ productType: 2 }, { recruitChapterCount: 0 }));
  assert.equal(list.some((item) => item.key === 'club'), false, '俱乐部不邀俱乐部(R6)');
  assert.deepEqual(keys(list).slice(0, 1), ['merchant:off']);
});

test('点灰掉的按钮只提示原因,不打开抽屉', () => {
  const page = makePage();
  const tips = [];
  global.getApp().tips = (t) => tips.push(t);
  page.data.quickActions = [{ key: 'club', disabled: true, disabledReason: '原因' }];
  let opened = false;
  page.openClubSheet = () => { opened = true; };
  page.onQuickAction({ currentTarget: { dataset: { key: 'club' } } });
  assert.equal(opened, false);
  const wxml = fs.readFileSync(HOST_WXML, 'utf8');
  assert.match(wxml, /class="qa-btn \{\{ item\.disabled \? 'qa-btn--off' : '' \}\}"/);
});
