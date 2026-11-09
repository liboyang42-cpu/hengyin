// 第十轮 f1 item 2(j1 F1/F2,2026-11-04 owner:商家发活动不必带玩法)。
// 钉住:① 模板选填(分步判据、提交校验、取消选中都不再要模板);② 进页就问 /api/activity/publish_precheck,
// 「当前身份不可创建活动」这类拒绝在进页时讲,不等填完三步按发布;③ 去建玩法时活动页留在栈里,
// 编辑器发布后回到它(不 reLaunch 冲掉草稿),商家线进玩法引导带 scope=MERCHANT。
const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');

const PAGE = '../../pages/publish/activity/index.js';
const PROJECT_LIST = '/subpackageA/pages/myproject/index';
const FREE_ONLY = '商家主办活动还没开通收款，票价只能是 0'; // 已撤的旧文案,只用于反向断言

let sent = [];
let navigations = [];
let toasts = [];
let pageConfig = null;
let durable;

global.getApp = () => ({
  globalData: { statusBarHeight: 20, navBarHeight: 44 },
  sendRequest: (p) => { sent.push(p); },
  tips: (m) => { toasts.push(m); },
  getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
  chooseImage: () => {},
  getUserID: () => 9,
  getUserInfo: () => null,
  getToken: () => '',
});
global.wx = {
  getStorageSync: key => durable.getStorageSync(key),
  setStorageSync: (key, value) => durable.setStorageSync(key, value),
  removeStorageSync: key => durable.removeStorageSync(key),
  showToast: (o) => { toasts.push(o && o.title); },
  showLoading: () => {},
  hideLoading: () => {},
  showModal: (o) => { if (o.success) o.success({ confirm: true }); },
  navigateTo: (o) => { navigations.push(o.url); },
  redirectTo: (o) => { navigations.push('redirect:' + o.url); },
  navigateBack: () => { navigations.push('back'); },
  pageScrollTo: () => {},
  nextTick: (f) => f(),
  previewImage: () => {},
};

global.Page = (cfg) => { pageConfig = cfg; };

beforeEach(() => {
  durable = require('../helpers/memory-storage.js')();
  sent = [];
  navigations = [];
  toasts = [];
  pageConfig = null;
  delete require.cache[require.resolve(PAGE)];
  require(PAGE);
});

function makePage() {
  const inst = Object.assign({}, pageConfig);
  inst.data = JSON.parse(JSON.stringify(pageConfig.data));
  // 这些付费保存/路由测试沿用已登记主理人；未登记与免费切换由独立真实采集回归覆盖。
  inst.data.identityRegistered = true;
  inst.setData = function (patch, cb) {
    Object.keys(patch).forEach((k) => {
      const parts = k.replace(/\[(\d+)\]/g, '.$1').split('.');
      let o = inst.data;
      for (let i = 0; i < parts.length - 1; i++) {
        if (o[parts[i]] == null) o[parts[i]] = {};
        o = o[parts[i]];
      }
      o[parts[parts.length - 1]] = patch[k];
    });
    if (cb) cb();
  };
  return inst;
}

function last(url) { return sent.filter((r) => r.url === url).pop(); }
const PROJECT_MANAGE = 'merchant:project:manage';
function allowMerchant() {
  last('/api/merchant/access/me').success({ code: '200', data: {
    active: true, merchant: { id: 5, name: '梧桐里书房', logo: '' }, roleCode: 'MERCHANT_OWNER', permissions: [PROJECT_MANAGE] } });
}
function fillWithoutGame(page) {
  page.setData({
    selectedCategoryIds: [1],
    'formData.name': '书店夜读', 'formData.addressName': '复兴西路', 'formData.description': '一起读书',
    'formData.startDate': '2026-11-20 19:00:00', 'formData.endDate': '2026-11-20 21:00:00',
    'formData.templateId': 0, 'formData.imgUrl': 'cover.jpg',
    'formData.tickets': [{ name: '单人票', price: 39, totalStock: 20, description: '' }],
  });
}

test('商家进页:岗位放行后先问 publish_precheck(带 scope),回包前不出表单', () => {
  const page = makePage();
  page.onLoad({ scope: 'MERCHANT' });
  allowMerchant();
  const pre = last('/api/activity/publish_precheck');
  assert.ok(pre, '岗位放行后必须问后端能不能发');
  assert.deepEqual(pre.data, { scope: 'MERCHANT' });
  assert.equal(page.data.accessState, 'checking');
  pre.success({ code: 200, msg: '操作成功' });
  assert.equal(page.data.accessState, 'ready');
});

test('配额/身份拒绝在进页时讲:403 → denied,原因就是后端那句', () => {
  const page = makePage();
  page.onLoad({ scope: 'MERCHANT' });
  allowMerchant();
  last('/api/activity/publish_precheck').success({ code: 403, msg: '当前身份不可创建活动' });
  assert.equal(page.data.accessState, 'denied');
  assert.equal(page.data.deniedReason, '当前身份不可创建活动');
});

test('俱乐部线同样先问;编辑已有活动不问(不占新配额)', () => {
  const club = makePage();
  club.onLoad({});
  last('/api/publish/home').success({ code: '200', data: { role: 'club' } });
  assert.deepEqual(last('/api/activity/publish_precheck').data, {});
  sent = [];
  const edit = makePage();
  edit.onLoad({ id: '77', scope: 'MERCHANT' });
  allowMerchant();
  assert.equal(last('/api/activity/publish_precheck'), undefined);
  assert.equal(edit.data.accessState, 'ready');
});

test('不选玩法:第二步可继续,提交校验通过,不出「请选择模板」', () => {
  const page = makePage();
  fillWithoutGame(page);
  assert.equal(page.stepIsComplete(1, page.data), true);
  assert.equal(page.validateForm(), true);
  assert.equal(page.data.errors.templateId, undefined);
});

test('选过的玩法可以取消:取消选中后点完成 = 不带玩法', () => {
  const page = makePage();
  page.setData({ popTemp: true, selectedTempId: 5, selectedTempInfo: { id: 5, title: '书店问答' }, 'formData.templateId': 5 });
  page.selectTemp({ currentTarget: { dataset: { item: { id: 5, title: '书店问答' } } } });
  page.confirmTemp();
  assert.equal(page.data.popTemp, false);
  assert.equal(page.data.formData.templateId, 0);
  assert.equal(toasts.includes('请先选择一个模板'), false);
});

test('商家线去建玩法:引导页带 scope=MERCHANT,回来刷新模板列表', () => {
  const page = makePage();
  page.onLoad({ scope: 'MERCHANT' });
  allowMerchant();
  last('/api/activity/publish_precheck').success({ code: 200 });
  page.createTemplate();
  assert.equal(navigations.pop(), '/pages/publish/template-intro/index?scope=MERCHANT');
  sent = [];
  page.onHide();
  page.onShow();
  assert.ok(last('/api/template/my-list'), '回到发活动页要重拉模板列表');
});

test('编辑器发布后:活动页在栈里就回到它,不 reLaunch 冲掉草稿', () => {
  const fs = require('node:fs');
  const path = require('node:path');
  const vm = require('node:vm');
  const src = fs.readFileSync(path.join(__dirname, '../../pages/publish/temp/index.js'), 'utf8');
  const fn = /function backToActivityDraft\(\) \{[\s\S]*?\n\}/.exec(src)[0];
  const run = (routes) => {
    const calls = [];
    const ctx = { wx: { navigateBack: (o) => calls.push(o) }, getCurrentPages: () => routes.map((route) => ({ route })) };
    const ok = vm.runInNewContext(fn + '\nbackToActivityDraft()', ctx);
    return { ok, calls: JSON.parse(JSON.stringify(calls)) };
  };
  const fromActivity = run(['pages/merchant/index/index', 'pages/publish/activity/index', 'pages/publish/template-intro/index', 'pages/publish/temp/index']);
  assert.equal(fromActivity.ok, true);
  assert.deepEqual(fromActivity.calls, [{ delta: 2 }]);
  const plain = run(['pages/publish/fabu/index', 'pages/publish/template-intro/index', 'pages/publish/temp/index']);
  assert.equal(plain.ok, false);
  assert.deepEqual(plain.calls, []);
  assert.match(src, /onResultSheetClose\(\) \{[\s\S]{0,200}?if \(backToActivityDraft\(\)\) return;/);
  assert.match(src, /flow\[key\] === 'templates' && !backToActivityDraft\(\)\) wx\.reLaunch/);
});
