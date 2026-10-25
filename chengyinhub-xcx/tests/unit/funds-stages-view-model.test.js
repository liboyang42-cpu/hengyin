// 9-16 晚拍板第 5 条:余额页如实三段。缺字段整块判「取不到」,绝不把缺失渲成 0。
const test = require('node:test');
const assert = require('node:assert');
const { buildFundsStages } = require('../../components/cy/funds-stages/view-model.js');

test('三段 + 涉诉单列:客诉期按可提现日逐条标「X月X日可提现」', () => {
  const vm = buildFundsStages({
    amountsKnown: true,
    pendingSettlement: 90,
    complaintPeriod: [{ amount: 45.5, availableDate: '2026-09-24' }, { amount: 0, availableDate: '2026-09-25' }],
    disputed: '18.00',
    withdrawable: 12,
  });
  assert.deepStrictEqual(vm, {
    pending: '90.00',
    complaintPeriod: [{ key: '2026-09-24', label: '客诉期中 · 9月24日可提现', amount: '45.50' }],
    disputed: '18.00',
    withdrawable: '12.00',
    amountsKnown: true,
  });
});

test('字段缺失/非数/日期坏 ⇒ null(页面显示取不到,不显示 ¥0)', () => {
  const ok = { pendingSettlement: 0, complaintPeriod: [], disputed: 0, withdrawable: 0 };
  assert.ok(buildFundsStages(ok));
  assert.strictEqual(buildFundsStages(null), null);
  assert.strictEqual(buildFundsStages({ ...ok, withdrawable: undefined }), null);
  assert.strictEqual(buildFundsStages({ ...ok, disputed: 'abc' }), null);
  assert.strictEqual(buildFundsStages({ ...ok, complaintPeriod: undefined }), null);
  assert.strictEqual(buildFundsStages({ ...ok, complaintPeriod: [{ amount: 1, availableDate: '9/24' }] }), null);
});

test('后端说费率算不出(amountsKnown=false)时如实提示', () => {
  const vm = buildFundsStages({ amountsKnown: false, pendingSettlement: 0, complaintPeriod: [], disputed: 0, withdrawable: 5 });
  assert.strictEqual(vm.amountsKnown, false);
  assert.strictEqual(vm.pending, '');
});

// release-0917 集成:/api/wallet/stages 是 @RequestBody 端点;不带 JSON 头 = urlencoded ⇒ 415 → code 500,
// 三段恒显示「取不到」(lint:requestbody 判红即此)。这里按组件真实执行抓请求,钉住 header。
function captureStagesRequest(source) {
  const vm = require('node:vm');
  const requests = [];
  let definition;
  vm.runInNewContext(source, {
    getApp: () => ({ sendRequest: (o) => requests.push(o) }),
    require: () => ({ buildFundsStages: () => null }),
    Component: (v) => { definition = v; },
  });
  const comp = Object.assign({}, definition.methods, { data: {}, setData() {} });
  comp.load();
  return requests[0];
}

test('余额三段请求带 application/json 头(@RequestBody 端点)', () => {
  const fs = require('node:fs');
  const path = require('node:path');
  const src = fs.readFileSync(path.join(__dirname, '../../components/cy/funds-stages/index.js'), 'utf8');
  const req = captureStagesRequest(src);
  assert.strictEqual(req.url, '/api/wallet/stages');
  assert.strictEqual(req.method, 'POST');
  assert.strictEqual(String((req.header || {})['content-type'] || '').toLowerCase(), 'application/json');
});

// CU-C-92:提现入口能不能用由「可提现余额」决定,而余额只有本组件这一处数据源。
// 组件把归一化后的结论通过 bind:stages 抛给宿主页(俱乐部分润页据此禁用提现主按钮),
// 页面不必再打一次同一接口 —— 两个数迟早漂成两说。
// 负控:删掉 load() 里的 emitStages 调用,本组用例全红(事件收不到)。
function runStagesLoad(source, build, response) {
  const vm = require('node:vm');
  const requests = [];
  const events = [];
  let definition;
  vm.runInNewContext(source, {
    getApp: () => ({ sendRequest: (o) => requests.push(o) }),
    require: () => ({ buildFundsStages: build }),
    Component: (v) => { definition = v; },
  });
  const comp = Object.assign({}, definition.methods, {
    data: { showWithdrawable: true },
    setData(patch) { Object.assign(this.data, patch); },
    triggerEvent(name, detail) { events.push({ name, detail }); },
  });
  comp.load();
  if (response === 'fail') requests[0].fail({ errMsg: 'request:fail' });
  else requests[0].success(response);
  return events;
}

// 事件对象在 vm 沙箱域里创建,deepStrictEqual 会因原型不同误报,逐字段核对。
function assertStagesEvent(events, expected, message) {
  assert.strictEqual(events.length, 1, message);
  assert.strictEqual(events[0].name, 'stages', message);
  assert.strictEqual(events[0].detail.known, expected.known, message);
  assert.strictEqual(events[0].detail.positive, expected.positive, message);
}

function stagesSource() {
  const fs = require('node:fs');
  const path = require('node:path');
  return fs.readFileSync(path.join(__dirname, '../../components/cy/funds-stages/index.js'), 'utf8');
}

function mountStages(sharedApp) {
  const requests = [], events = []
  let definition
  const app = { globalData: sharedApp ? sharedApp.globalData : { user_id: 990001, role: 'merchant', user_type: 2 },
    getUserID() { return this.globalData.user_id }, sendRequest: o => requests.push(o) }
  require('node:vm').runInNewContext(stagesSource(), {
    getApp: () => app, require: () => ({ buildFundsStages }), Component: d => { definition = d },
  })
  const comp = Object.assign({}, definition.methods, {
    data: { ...definition.data, showWithdrawable: true },
    setData(p) { Object.assign(this.data, p) }, triggerEvent(name, detail) { events.push({ name, detail }) },
  })
  return { comp, requests, events, app, definition }
}
const walletResponse = amount => ({ code: 200, data: { pendingSettlement: 0,
  complaintPeriod: [], disputed: 0, withdrawable: amount, amountsKnown: true } })

test('真实请求封装的余额首登和权限拒绝只回传当前账号结果，换号401不自动登录', () => {
  const h = mountStages(); let logins = 0
  const client = require('../../utils/transport/request-client.js').createRequestClient({
    wxRequest: o => { h.requests.push(o); return { abort() {} } }, getBaseUrl: () => 'http://127.0.0.1',
    getUserID: () => h.app.getUserID(), reLogin: cb => {
      logins += 1; h.app.globalData.user_id = 990001; cb(true, { isCurrentSession: () => true })
    },
  })
  h.app.sendRequest = o => client.send(o); h.app.globalData.user_id = ''; h.comp.load()
  h.requests[0].success({ statusCode: 401, data: { code: 401 } })
  // 2026-10-24 owner 拍板「游客可浏览，写入时弹窗登录」:游客读私有余额 401 不再静默首登建号。
  assert.strictEqual(logins, 0); assert.strictEqual(h.requests.length, 1); assert.strictEqual(h.comp.data.stages, null)
  h.app.globalData.user_id = 990001; h.comp.load()
  h.requests[1].success({ statusCode: 200, data: walletResponse(20) })
  assert.strictEqual(h.comp.data.stages.withdrawable, '20.00'); assert.strictEqual(h.events[h.events.length - 1].detail.known, true)
  h.comp.load(); h.requests[2].success({ statusCode: 403, data: { msg: '无权限' } })
  assert.strictEqual(h.comp.data.stages, null); assert.strictEqual(h.events[h.events.length - 1].detail.known, false)
  h.comp.load(); h.app.globalData.user_id = 990002
  h.requests[3].success({ statusCode: 401, data: { code: 401 } })
  assert.strictEqual(logins, 0); assert.strictEqual(h.requests.length, 4); assert.strictEqual(h.comp.data.stages, null)
})

test('余额换号后重读清除旧值，旧成功/失败不覆盖新余额或重新发出旧可提现事件', () => {
  const h = mountStages(); h.comp.load(); h.requests[0].success(walletResponse(60))
  h.comp.load(); const old = h.requests[1]; h.app.globalData.user_id = 990002; h.comp.load()
  assert.strictEqual(h.comp.data.stages, null); assert.strictEqual(h.comp.data.hasRows, false)
  h.requests[2].success(walletResponse(20)); const events = h.events.length
  old.success(walletResponse(60)); old.fail()
  assert.strictEqual(h.comp.data.stages.withdrawable, '20.00')
  assert.strictEqual(h.comp.data.state, 'ready'); assert.strictEqual(h.events.length, events)
})

test('余额身份或角色失效没有新读取时清除显示，不向宿主发送旧账号的金额结论', () => {
  for (const change of [app => { app.globalData.user_id = 990002 },
    app => { app.globalData.role = 'player' }, app => { app.globalData.user_type = 1 },
    app => { app.globalData.user_id = '' }]) {
    const h = mountStages(); h.comp.load(); change(h.app); h.requests[0].success(walletResponse(60))
    assert.strictEqual(h.comp.data.stages, null); assert.strictEqual(h.comp.data.hasRows, false)
    assert.strictEqual(h.events.length, 0); assert.strictEqual(h.requests.length, 1)
  }
})

test('余额隐藏或卸载后拒绝在途结果，返回重读，初次show不重复请求', () => {
  const h = mountStages(); h.definition.lifetimes.attached.call(h.comp)
  h.definition.pageLifetimes.show.call(h.comp); assert.strictEqual(h.requests.length, 1)
  h.definition.pageLifetimes.hide.call(h.comp); h.requests[0].success(walletResponse(60))
  assert.strictEqual(h.comp.data.stages, null); assert.strictEqual(h.events.length, 0)
  h.definition.pageLifetimes.show.call(h.comp); h.requests[1].success(walletResponse(20))
  h.definition.lifetimes.detached.call(h.comp); const events = h.events.length
  h.comp.load(); h.requests[1].fail()
  assert.strictEqual(h.requests.length, 2); assert.strictEqual(h.comp.data.state, 'ready')
  assert.strictEqual(h.events.length, events)
})

test('余额匿名首登只采用当前身份重新读取的金额，刷新开始与失败都使宿主余额未知', () => {
  const h = mountStages(); h.app.globalData.user_id = ''; h.comp.load()
  h.app.globalData.user_id = 990001; h.requests[0].success(walletResponse(60))
  assert.strictEqual(h.requests.length, 2); assert.strictEqual(h.comp.data.stages, null)
  h.requests[1].success(walletResponse(20)); h.comp.load()
  assert.strictEqual(h.comp.data.stages, null)
  assert.strictEqual(h.events[h.events.length - 1].detail.known, false)
  h.requests[2].fail(); assert.strictEqual(h.comp.data.state, 'error')
  assert.strictEqual(h.events[h.events.length - 1].detail.positive, false)
})

test('余额旧组件结果不会把俱乐部新账号的零余额改成可提现', () => {
  const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
  const requests = []; let definition
  const app = { globalData: { user_id: 990001, role: 'merchant', user_type: 2 },
    getUserID() { return this.globalData.user_id }, sendRequest: o => requests.push(o) }
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../pages/club/settlement/index.js'), 'utf8'), {
    getApp: () => app, Page: d => { definition = d },
    require: () => ({ bizFailureMessage: (res, fallback) => fallback }),
    wx: { stopPullDownRefresh() {} },
  })
  const page = { ...definition, data: { ...definition.data }, setData(p) { Object.assign(this.data, p) } }
  const summary = { code: 200, data: { settledAmountText: '¥20.00', settledAmountStatus: 'verified',
    unverifiedSettledCount: 0, topics: [], isOwner: true } }
  page.onLoad({ clubId: 21 }); requests[0].success(summary)
  const oldChild = mountStages(app); oldChild.comp.triggerEvent = (name, detail) => page.onStages({ detail })
  oldChild.comp.load(); app.globalData.user_id = 990002; page.loadSummary(); requests[1].success(summary)
  const child = mountStages(app); child.comp.triggerEvent = (name, detail) => page.onStages({ detail })
  child.comp.load(); child.requests[0].success(walletResponse(0)); assert.strictEqual(page.data.withdrawState, 'zero')
  oldChild.requests[0].success(walletResponse(60))
  assert.strictEqual(page.data.withdrawState, 'zero'); assert.strictEqual(oldChild.comp.data.stages, null)
})

test('CU-C-92:可提现余额归一化后抛给宿主页(0 ⇒ 不可提、>0 ⇒ 可提)', () => {
  const zero = runStagesLoad(stagesSource(), () => ({
    pending: '', complaintPeriod: [], disputed: '', withdrawable: '0.00', amountsKnown: true,
  }), { code: 200, data: {} });
  assertStagesEvent(zero, { known: true, positive: false }, '可提现 0 必须报不可提');

  const positive = runStagesLoad(stagesSource(), () => ({
    pending: '', complaintPeriod: [], disputed: '', withdrawable: '12.00', amountsKnown: true,
  }), { code: 200, data: {} });
  assertStagesEvent(positive, { known: true, positive: true });
});

test('CU-C-92:余额算不出/取不到时 resolved 为 unknown,不得当成 0 也不得当可提', () => {
  const cases = [
    ['amountsKnown=false(费率算不出，余额会被低估)', () => ({ withdrawable: '0.00', amountsKnown: false }), { code: 200, data: {} }],
    ['响应形状不合法', () => null, { code: 200, data: {} }],
    ['请求失败', () => ({ withdrawable: '9.00', amountsKnown: true }), 'fail'],
  ];
  for (const [why, build, response] of cases) {
    const events = runStagesLoad(stagesSource(), build, response);
    assertStagesEvent(events, { known: false, positive: false }, why);
  }
});
