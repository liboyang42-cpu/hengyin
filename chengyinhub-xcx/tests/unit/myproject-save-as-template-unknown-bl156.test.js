// BL-156:为 #164(BL-120)myproject `_postSaveAsTemplate` 补处理器单测:结果未知/失败时不提示成功、
// 重复点击不重复发请求。与 myproject-save-as-template.test.js(#164 自带)不重复。不改被测代码;依赖 #164,须在其后合并。
const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');

const PAGE = '../../subpackageA/pages/myproject/index.js';

let sent = [];
let toasts = [];
let modalAnswer = true;
let modals = [];
let throwOnSend = false;

global.getApp = () => ({
  globalData: { statusBarHeight: 20, navBarHeight: 44 },
  sendRequest: (p) => { if (throwOnSend) throw new Error('boom'); sent.push(p); },
  getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
  getUserID: () => 1,
  getToken: () => '',
});
global.wx = {
  getStorageSync: () => undefined,
  setStorageSync: () => {},
  showToast: (o) => { toasts.push(o && o.title); },
  showLoading: () => {}, hideLoading: () => {},
  showModal: (o) => { modals.push(o); if (o.success) o.success({ confirm: modalAnswer, cancel: !modalAnswer }); },
  navigateTo: () => {}, redirectTo: () => {},
  getSystemInfoSync: () => ({ windowWidth: 375 }),
  nextTick: (f) => f(),
  setNavigationBarColor: () => {}, setNavigationBarTitle: () => {},
};

let pageConfig = null;
global.Page = (cfg) => { pageConfig = cfg; };

beforeEach(() => {
  sent = []; toasts = []; modals = []; modalAnswer = true; throwOnSend = false;
  pageConfig = null;
  delete require.cache[require.resolve(PAGE)];
  require(PAGE);
});

function makePage() {
  const inst = Object.assign({}, pageConfig);
  inst.data = JSON.parse(JSON.stringify(pageConfig.data));
  inst.setData = function (patch) {
    Object.keys(patch).forEach((k) => {
      if (k.indexOf('.') > 0) { const [a, b] = k.split('.'); inst.data[a][b] = patch[k]; }
      else inst.data[k] = patch[k];
    });
  };
  inst.reloads = 0;
  inst.reload = function () { inst.reloads++; };
  inst.loadTemplates = function () {};
  return inst;
}


const SUCCESS_TEXT = '已另存为主题模板';

test('断网(fail 无回包):提示网络异常,不提示成功、不回读', () => {
  const page = makePage();
  page._postSaveAsTemplate(21);
  sent[0].fail(undefined);
  assert.deepEqual(toasts, ['网络异常，请重试']);
  assert.equal(page.reloads, 0);
});

test('HTTP 5xx/网关超时(request-client 回落到 fail(data, status)):不提示成功、不回读', () => {
  const page = makePage();
  page._postSaveAsTemplate(22);
  sent[0].fail('<html>504 Gateway Time-out</html>', 504);
  assert.equal(toasts.length, 1);
  assert.notEqual(toasts[0], SUCCESS_TEXT);
  assert.equal(page.reloads, 0);
});

test('业务失败且无 msg:用「另存为模板失败」兜底,不回读', () => {
  const page = makePage();
  page._postSaveAsTemplate(23);
  sent[0].success({ code: '500' });
  assert.deepEqual(toasts, ['另存为模板失败']);
  assert.equal(page.reloads, 0);
});

test('回包为空:按失败处理,不提示成功', () => {
  const page = makePage();
  page._postSaveAsTemplate(24);
  sent[0].success(undefined);
  assert.deepEqual(toasts, ['另存为模板失败']);
  assert.equal(page.reloads, 0);
});

test('成功但后端未给 msg:用固定成功文案并回读', () => {
  const page = makePage();
  page._postSaveAsTemplate(25);
  sent[0].success({ code: 200 });
  assert.deepEqual(toasts, [SUCCESS_TEXT]);
  assert.equal(page.reloads, 1);
});

test('sendRequest 同步抛错:提示网络异常并释放在途锁,可再次发起', () => {
  const page = makePage();
  throwOnSend = true;
  page._postSaveAsTemplate(26);
  assert.deepEqual(toasts, ['网络异常，请重试']);
  assert.ok(!page._savingTplIds[26], '抛错后在途锁已释放');
  throwOnSend = false;
  page._postSaveAsTemplate(26);
  assert.equal(sent.length, 1);
});

test('在途期间重复点击只发一次;请求结束(成功或失败)后才放行', () => {
  const page = makePage();
  page._postSaveAsTemplate(27);
  page._postSaveAsTemplate(27);
  page._postSaveAsTemplate(27);
  assert.equal(sent.length, 1, '在途时不重复发');
  sent[0].success({ code: '200' });
  sent[0].complete && sent[0].complete();
  page._postSaveAsTemplate(27);
  assert.equal(sent.length, 2, '成功结束后放行');
});

test('在途锁按主题 id 区分:A 在途不挡 B', () => {
  const page = makePage();
  page._postSaveAsTemplate(28);
  page._postSaveAsTemplate(29);
  assert.deepEqual(sent.map((r) => r.data.id), [28, 29]);
});
