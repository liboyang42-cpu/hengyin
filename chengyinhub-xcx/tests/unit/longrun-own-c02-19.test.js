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

test('server gather coordinates survive edit detail, ticket drawer and save including zero latitude', () => {
  const page = makePage();
  page.data.editingTopicId = '77';
  page.applyEditingTopic({ topic:{ id:77, updateTime:'v1', productType:2 }, chapters:[], tickets:[{
    id:9, name:'ticket', totalInventory:20, gatherLng:'121.48', gatherLat:0,
    meetingPoint:'point', startTime:'2030-01-01', endTime:'2030-01-02', saleStartTime:'2029-12-01', saleEndTime:'2029-12-31'
  }] });
  page.openTicketEditor({ currentTarget:{ dataset:{ index:0 } } });
  assert.equal(page.data.editingTicket.meetingPointLongitude, '121.48');
  assert.equal(page.data.editingTicket.meetingPointLatitude, 0);
  assert.equal(page.data.canSaveTicket, true);
  page.saveTicket();
  assert.equal(page.data.formData.tickets[0].gatherLng, 121.48);
  assert.equal(page.data.formData.tickets[0].gatherLat, 0);
});
