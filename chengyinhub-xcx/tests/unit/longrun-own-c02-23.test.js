// Real Page methods, deferred request callbacks and local storage exercise lost-write responses.
const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');

const PAGE = '../../pages/publish/fabu/index.js';

let sent = [];
let storage = {};
let navigations = [];
let toasts = [];
let modals = [];
let memberId = 42;

global.getApp = () => ({
  globalData: { statusBarHeight: 20, navBarHeight: 44 },
  sendRequest: (p) => { sent.push(p); },
  tips: (m) => { toasts.push(m); },
  getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
  chooseImage: () => {},
  getUserID: () => memberId,
  getUserInfo: () => null,
  getToken: () => '',
});
global.wx = {
  getStorageSync: (k) => storage[k],
  setStorageSync: (k, v) => { storage[k] = v; },
  removeStorageSync: (k) => { delete storage[k]; },
  getSystemInfoSync: () => ({ windowWidth: 375 }),
  showToast: (o) => { toasts.push(o && o.title); },
  showLoading: () => {}, hideLoading: () => {},
  showModal: (options) => { modals.push(options); if (options.success) options.success({ confirm: true }); },
  navigateTo: (options) => { navigations.push(options.url); },
  redirectTo: (options) => { navigations.push(options.url); },
  createMapContext: () => ({ getCenterLocation: () => {} }),
  pageScrollTo: () => {}, nextTick: (f) => f(),
};

let pageConfig = null;
global.Page = (cfg) => { pageConfig = cfg; };

beforeEach(() => {
  memberId = 42;
  sent = [];
  storage = {};
  navigations = [];
  toasts = [];
  modals = [];
  pageConfig = null;
  delete require.cache[require.resolve(PAGE)];
  require(PAGE);
});

function makePage() {
  const inst = Object.assign({}, pageConfig);
  inst.data = JSON.parse(JSON.stringify(pageConfig.data));
  inst.setData = function (patch, cb) {
    Object.keys(patch).forEach((k) => {
      const parts = k.replace(/\[(\d+)\]/g, '.$1').split('.');
      let o = inst.data;
      for (let i = 0; i < parts.length - 1; i++) o = o[parts[i]];
      o[parts[parts.length - 1]] = patch[k];
    });
    if (cb) cb();
  };
  return inst;
}

test('editing an automatic route to a nonadjacent target gives it mapping identity that survives reordering', () => {
  const page = makePage();
  page.data.formData.productType = 1;
  page.data.formData.chapters = [{ _localId:'chapter', name:'chapter', nodes:['A','B','C','D'].map((id,i)=>({ _localId:id, name:id, longitude:121+i/100, latitude:31, nodeTime:10 })) }];
  page.data.formData.routeMode = 'BRANCH_GRAPH';
  page._routeGraph = page._sequentialRouteGraph(page._routeNodes());
  page._routeMappingSourceNodeId = 'A';
  page.data.routeMappingRows = [{ code:'COMPLETED', triggerType:'CHOICE', toNodeId:'D' }];
  page.finishNodeRouteMapping();
  const mapped = page._routeGraph.edges.find(edge=>edge.fromNodeId==='A');
  assert.match(mapped.id, /^mapping_/);
  assert.equal(mapped.toNodeId, 'D');
  page._reorderNode('chapter','B',1);
  assert.equal(page._routeGraph.edges.find(edge=>edge.id===mapped.id).toNodeId, 'D');
});
