// CL-29 手机号按需索取 / 无强弹 / 真实入口核对(refs F-L5C-04, F-W1-14, F-W1-09)。
//
// 服务端唯一校验「已绑定手机号」的写路径是账号注销(ApiAccountDeregistrationController.apply),
// 无号时走微信本人确认 —— 前端 deregister-wechat-component.test.js、后端
// ApiAccountDeregistrationControllerTest 已钉。报名(/api/registration/create)要的是
// 「联系电话」表单字段(RegistrationRequest.phone @NotBlank),不是绑定号;提现要的是银行卡预留号表单。
// 这里把剩下的真实入口装进沙箱跑页面自己的 JS(wx 打桩),断言:
//   1. 活动报名:参与人缺手机号 ⇒ 不建单、提示并直接带去参与人填写页(按需,有出路)。
//   2. 资料页:未绑号时渲染 getPhoneNumber 按钮,授权后把 code 交给绑定接口;拒绝授权不发请求。
//   3. 全仓:没有任何页面在 onLoad/onShow 等生命周期里自行索取手机号(首页规则推广到每一页)。
// getPhoneNumber 真正弹窗与换号是付费真机能力,仍是 NEEDS_RUNTIME。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');

function setByPath(target, dataPath, value) {
  const parts = dataPath.split('.');
  let cursor = target;
  parts.slice(0, -1).forEach((part) => {
    if (!cursor[part] || typeof cursor[part] !== 'object') cursor[part] = {};
    cursor = cursor[part];
  });
  cursor[parts[parts.length - 1]] = value;
}

function loadPage(rel, appOverrides) {
  const file = path.join(ROOT, rel);
  const calls = { tips: [], toasts: [], requests: [], navigations: [] };
  let config = null;
  const previous = { getApp: global.getApp, Page: global.Page };
  global.getApp = () => Object.assign({
    globalData: { user_id: 9, features: {}, statusBarHeight: 20, navBarHeight: 44 },
    getUserID: () => 9,
    isDevEnv: () => false,
    tips: (m) => calls.tips.push(m),
    sendRequest: (o) => calls.requests.push(o),
    recordConsent: () => Promise.resolve(),
  }, appOverrides || {});
  global.Page = (value) => { config = value; };
  global.wx = {
    getStorageSync: () => '',
    setStorageSync() {},
    removeStorageSync() {},
    getWindowInfo: () => ({ statusBarHeight: 20 }),
    getSystemInfoSync: () => ({ statusBarHeight: 20 }),
    showToast: (o) => calls.toasts.push(o && o.title),
    showModal() {},
    showLoading() {},
    hideLoading() {},
    hideToast() {},
    navigateTo: (o) => calls.navigations.push(o.url),
    redirectTo: (o) => calls.navigations.push(o.url),
  };
  delete require.cache[file];
  try { require(file); } finally {
    global.getApp = previous.getApp;
    global.Page = previous.Page;
  }
  const page = Object.assign({}, config);
  page.data = JSON.parse(JSON.stringify(config.data));
  page.setData = function (patch, cb) {
    Object.entries(patch).forEach(([k, v]) => setByPath(this.data, k, v));
    if (cb) cb();
  };
  return { page, calls };
}

// ---------- 1. 活动报名:按需索取,带去参与人填写页 ----------

function readyBaoming(userInfo, addressList) {
  const h = loadPage('pages/activity/baoming/baoming.js');
  const submits = [];
  Object.assign(h.page.data, {
    pageState: 'ready',
    activityId: 12,
    activityInfo: { id: 12 },
    selectedTicket: { id: 34, price: 88 },
    selectedAddress: { id: 7, fullName: '林一', mobilePhone: '' },
    addressList: addressList,
    participantState: 'ready',
    userInfo: userInfo,
    hostShareChecked: true,
    hostShareConsentReady: true,
    totalAmount: 88,
    paymentReady: true,
    canPay: true,
  });
  h.page._hostShareConsentReady = true;
  h.page._quoteSign = 'quote-signed';
  h.page._signupSubscriptionRequested = true;
  h.page._workflow = { submit(payload) { submits.push(payload); return true; } };
  return Object.assign(h, { submits });
}

test('CL-29 报名:参与人缺手机号时不建单,提示并直接带去参与人填写页(列表为空)', () => {
  const h = readyBaoming({ realName: '林一', phone: '', email: '', point: 0 }, []);
  h.page.handlePayment();
  assert.equal(h.submits.length, 0, '缺号仍建单 ⇒ 后端只会回「手机号不能为空」,用户无出路');
  assert.ok(!h.calls.requests.some((r) => /\/api\/registration\/create/.test(r.url || '')));
  assert.deepEqual(h.calls.tips, ['参与人信息需要包含手机号']);
  assert.deepEqual(h.calls.navigations, ['/pages/addressinfo/addressinfo?mode=participant'],
    '缺号必须给出填写入口，而不是只弹一句错误');
});

test('CL-29 报名:已有参与人列表时缺号改为打开参与人选择弹窗,仍不建单', () => {
  const h = readyBaoming({ realName: '林一', phone: '', email: '', point: 0 },
    [{ id: 7, fullName: '林一', mobilePhone: '' }]);
  let popups = 0;
  h.page.showAddressPopup = function () { popups += 1; };
  h.page.handlePayment();
  assert.equal(h.submits.length, 0);
  assert.equal(popups, 1, '有列表时应打开选择弹窗让用户换人/补号');
});

test('CL-29 报名:手机号齐全时才真正提交(对照组,证明上面拦截不是因为别的前置条件)', () => {
  const h = readyBaoming({ realName: '林一', phone: '13800138000', email: '', point: 0 }, []);
  h.page.data.selectedAddress.mobilePhone = '13800138000';
  h.page.handlePayment();
  assert.equal(h.submits.length, 1);
  assert.equal(h.submits[0].userInfo.phone, '13800138000');
});

// ---------- 2. 资料页:getPhoneNumber 绑定入口 ----------

test('CL-29 资料页:getPhoneNumber 绑定/换绑按钮常驻,按钮由用户点击触发', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/gerenziliao/gerenziliao.wxml'), 'utf8');
  const btn = wxml.match(/<button[^>]*open-type="getPhoneNumber"[^>]*>/g) || [];
  assert.equal(btn.length, 1);
  // 2026-10-27 owner 真机走查 P-56:已绑定也要有修改图标,点开走微信手机号授权换绑 ⇒ 不再按 !userInfo.phone 隐藏
  assert.doesNotMatch(btn[0], /wx:if=/);
  assert.match(btn[0], /aria-label="\{\{userInfo\.phone \? '更换手机号' : '绑定手机号'\}\}"/);
  assert.match(btn[0], /bindgetphonenumber="getPhoneNumber"/);
});

test('CL-29 资料页:授权成功把 code 交给 /api/getwxbindphone 并回填;拒绝授权不发请求', () => {
  const h = loadPage('pages/gerenziliao/gerenziliao.js');
  h.page.getPhoneNumber({ detail: { errMsg: 'getPhoneNumber:fail user deny' } });
  assert.equal(h.calls.requests.length, 0);
  assert.equal(h.page.data.userInfo.phone || '', '');

  h.page.getPhoneNumber({ detail: { errMsg: 'getPhoneNumber:ok', code: 'phone-code-1' } });
  assert.equal(h.calls.requests.length, 1);
  const req = h.calls.requests[0];
  assert.equal(req.url, '/api/getwxbindphone');
  assert.deepEqual(req.data, { code: 'phone-code-1' });
  req.success({ code: '200', data: { phone: '13800138000' } });
  assert.equal(h.page.data.userInfo.phone, '13800138000');
  assert.equal(h.page.data.phoneMasked, '138****8000');
});

// ---------- 3. 任何页面都不在生命周期里自行索取手机号 ----------

function listFiles(dir, exts, out) {
  for (const name of fs.readdirSync(dir)) {
    if (name === 'node_modules' || name === 'tests' || name === 'scripts' || name.startsWith('.')) continue;
    const full = path.join(dir, name);
    const st = fs.statSync(full);
    if (st.isDirectory()) listFiles(full, exts, out);
    else if (exts.includes(path.extname(name))) out.push(full);
  }
  return out;
}

const LIFECYCLE = /^(onLoad|onShow|onReady|attached|ready|show|created)$/;

// 取出某个下标所在的最近一层「方法名」(两格到六格缩进的 `name(` / `name: function(`)。
function enclosingMethod(src, index) {
  const before = src.slice(0, index).split('\n');
  for (let i = before.length - 1; i >= 0; i--) {
    const m = before[i].match(/^\s{2,6}(?:async\s+)?([A-Za-z_$][\w$]*)\s*(?:\([^)]*\)\s*\{|:\s*(?:async\s+)?function\b)/);
    if (m && !/^(if|for|while|switch|catch|function|success|fail|complete)$/.test(m[1])) return m[1];
  }
  return '';
}

test('CL-29 全仓:getPhoneNumber 只出现在首页(从不打开)与资料页(点击按钮)两处', () => {
  const wxmls = listFiles(ROOT, ['.wxml'], [])
    .filter((f) => /open-type="getPhoneNumber"/.test(fs.readFileSync(f, 'utf8')))
    .map((f) => path.relative(ROOT, f)).sort();
  assert.deepEqual(wxmls, ['pages/gerenziliao/gerenziliao.wxml', 'pages/index/index.wxml']);
  const indexWxml = fs.readFileSync(path.join(ROOT, 'pages/index/index.wxml'), 'utf8');
  assert.match(indexWxml, /<view class="tkbox" wx:if="\{\{showPhoneModal\}\}">/);
});

test('CL-29 全仓:没有任何代码把 showPhoneModal 置为 true(首页不强弹)', () => {
  const hits = listFiles(ROOT, ['.js'], [])
    .filter((f) => /showPhoneModal['"]?\s*[:=]\s*(true|!0|1\b)/.test(fs.readFileSync(f, 'utf8')))
    .map((f) => path.relative(ROOT, f));
  assert.deepEqual(hits, []);
});

test('CL-29 全仓:跳绑定入口(资料页)只发生在用户动作里,不在 onLoad/onShow 等生命周期', () => {
  const offenders = [];
  const seen = [];
  for (const f of listFiles(ROOT, ['.js'], [])) {
    const src = fs.readFileSync(f, 'utf8');
    const re = /url:\s*['"`]\/pages\/gerenziliao\/gerenziliao/g;
    let m;
    while ((m = re.exec(src))) {
      const name = enclosingMethod(src, m.index);
      const rel = path.relative(ROOT, f);
      seen.push(rel + '#' + name);
      if (!name || LIFECYCLE.test(name)) offenders.push(rel + '#' + (name || '?'));
    }
  }
  assert.ok(seen.length >= 3, '扫描应至少命中 shezhi/topic/profile 的资料页入口: ' + seen.join(','));
  assert.deepEqual(offenders, []);
});
