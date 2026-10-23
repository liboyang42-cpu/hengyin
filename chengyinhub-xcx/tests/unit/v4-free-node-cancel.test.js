// SC-FREE-NODE-CANCEL:商家在 merchantinfo 的自由点位表单(chapter-node-form)里
// 改了名称/地址后取消、或在地图选点(wx.chooseLocation)里取消 —— 取消不写任何东西;
// 手改过、没有地图坐标的地址不能被保存。
const { beforeEach, test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const ROOT = path.resolve(__dirname, '../..');
const COMPONENT = '../../pages/topic/components/cy/chapter-node-form/index.js';
const PAGE = 'pages/topic/merchantinfo/merchantinfo';

let componentConfig;
let requests;
let tips;
let modals;
let locationPicks;

global.getApp = () => ({
  getUserID: () => 1,
  sendRequest: (request) => requests.push(request),
  tips: (message) => tips.push(message),
});
global.Component = (config) => { componentConfig = config; };
global.wx = {
  navigateTo() {},
  showModal: (options) => modals.push(options),
  chooseLocation: (options) => locationPicks.push(options),
  showToast: (options) => tips.push(options.title),
};

beforeEach(() => {
  componentConfig = null;
  requests = [];
  tips = [];
  modals = [];
  locationPicks = [];
  delete require.cache[require.resolve(COMPONENT)];
  require(COMPONENT);
});

const PRESET = { id: 80, name: '咖啡点', templateId: 88, address: '星海街 8 号', longitude: '120.7', latitude: '31.3' };

function makeEditingForm() {
  const events = [];
  const component = Object.assign({}, componentConfig.methods);
  component.data = JSON.parse(JSON.stringify(componentConfig.data));
  Object.assign(component.data, {
    show: true, chapterId: 33, termsMode: 'PERK', formMode: 'node', submitting: false,
    presetNode: Object.assign({}, PRESET), defaultAddress: '',
  });
  component.setData = (patch, callback) => {
    Object.entries(patch).forEach(([key, value]) => {
      if (key.startsWith('form.')) component.data.form[key.slice(5)] = value;
      else component.data[key] = value;
    });
    if (callback) callback();
  };
  component.triggerEvent = (name, detail) => events.push({ name, detail });
  // 与 observers.show 一致:show=true 时 openForm
  component.openForm();
  return { component, events };
}

function input(component, key, value) {
  component.onInput({ currentTarget: { dataset: { key } }, detail: { value } });
}

test('改了名称和地址后关闭表单:只发 close、不发 submit,重开回到已保存的原点位', () => {
  const { component, events } = makeEditingForm();
  assert.equal(component.data.canSubmit, true, '原点位回填后本可保存');
  input(component, 'name', '改过的名字');
  input(component, 'address', '随手改的地址');
  component.onClose();
  assert.deepEqual(events.map(e => e.name), ['close'], '取消只能关闭，不得提交');
  assert.equal(requests.some(r => /chapter-node\/submit|chapter-application\/apply/.test(r.url)), false);

  // 父页把 show 置回 false 再打开 → observer 重新 openForm,改动被丢弃
  componentConfig.observers.show.call(component, false);
  componentConfig.observers.show.call(component, true);
  assert.equal(component.data.form.name, PRESET.name);
  assert.equal(component.data.form.address, PRESET.address);
  assert.equal(component.data.form.longitude, PRESET.longitude);
  assert.equal(component.data.form.latitude, PRESET.latitude);
  assert.equal(events.filter(e => e.name === 'submit').length, 0);
});

test('手改地址后在地图选点里取消:地址无坐标,保存被拦且不发 submit', () => {
  const { component, events } = makeEditingForm();
  input(component, 'address', '手写的无效地址');
  component.chooseNodeLocation();
  modals.at(-1).success({ confirm: true });
  locationPicks.at(-1).fail({ errMsg: 'chooseLocation:fail cancel' });
  assert.equal(component.data.form.address, '手写的无效地址', '取消选点不改写表单');
  assert.equal(component.data.form.longitude, '');
  assert.equal(component.data.form.latitude, '');
  assert.equal(component.data.locationReady, false);
  assert.equal(component.data.canSubmit, false);
  component.onSubmit();
  assert.equal(events.filter(e => e.name === 'submit').length, 0, '没有地图坐标的地址不能保存');
  assert.ok(tips.includes('请在地图选择到店位置'));
});

test('地图选点用途弹窗点取消:不唤起 chooseLocation,表单不变', () => {
  const { component, events } = makeEditingForm();
  const before = JSON.stringify(component.data.form);
  component.chooseNodeLocation();
  modals.at(-1).success({ confirm: false, cancel: true });
  assert.equal(locationPicks.length, 0);
  assert.equal(JSON.stringify(component.data.form), before);
  assert.equal(events.length, 0);
});

test('merchantinfo 页 closeChapterNodeForm 只隐藏表单,不发任何请求', () => {
  const pageRequests = [];
  let config = null;
  const app = {
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    getUserID: () => 7,
    sendRequest: (options) => pageRequests.push(options),
    getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
    tips() {},
  };
  const pageWx = {
    getStorageSync: () => '', setStorageSync() {}, removeStorageSync() {},
    showToast() {}, showLoading() {}, hideLoading() {}, showModal() {},
    navigateBack() {}, navigateTo() {}, redirectTo() {}, reLaunch() {}, switchTab() {}, pageScrollTo() {},
    getSystemInfoSync: () => ({ windowWidth: 375, statusBarHeight: 20 }),
    getWindowInfo: () => ({ windowWidth: 375, statusBarHeight: 20 }),
  };
  const context = vm.createContext({
    console,
    getApp: () => app,
    getCurrentPages: () => [],
    Page: (value) => { config = value; },
    require: (id) => require(id.startsWith('.') ? path.resolve(ROOT, 'pages/topic/merchantinfo', id) : id),
    module: { exports: {} },
    exports: {},
    wx: pageWx,
  });
  vm.runInContext(fs.readFileSync(path.join(ROOT, PAGE + '.js'), 'utf8'), context, { filename: PAGE + '.js' });
  const page = Object.assign({}, config, { data: JSON.parse(JSON.stringify(config.data)) });
  page.setData = (patch, cb) => { Object.assign(page.data, patch); if (cb) cb(); };
  page.data.chapterNodeFormVisible = true;
  page.data.chapterNodeSubmitting = false;
  page.closeChapterNodeForm();
  assert.equal(page.data.chapterNodeFormVisible, false);
  assert.equal(pageRequests.length, 0, '取消不写任何东西');
});
