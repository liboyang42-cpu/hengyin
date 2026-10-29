// CU-M-05(9-25 裁决):商家在「我的项目·活动」也要看到「发布单场活动」入口。
// 判据从「本地 role 缓存 == club」换成店主的「项目管理」岗位权限,且往下三次跳转
// (新建 / 编辑 / 待审核卡续写)必须带 scope=MERCHANT —— 不带就是按俱乐部口径判身份,
// 商家自己发的活动反而被发布页拦回门外。
const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');

const PAGE = '../../subpackageA/pages/myproject/index.js';
const PROJECT_MANAGE = 'merchant:project:manage';

let sent = [];
let toasts = [];
let navigations = [];
let pageConfig = null;
let actor = 1;
let pendingStorage = new Map();

global.getApp = () => ({
  globalData: { statusBarHeight: 20, navBarHeight: 44 },
  sendRequest: (p) => { sent.push(p); },
  getUserID: () => actor,
  getAuthorization: () => 'tk-1',
  getToken: () => 'tk-1',
  getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
});
global.wx = {
  getStorageSync: key => pendingStorage.get(key),
  setStorageSync: (key, value) => pendingStorage.set(key, value),
  removeStorageSync: key => pendingStorage.delete(key),
  showToast: (o) => { toasts.push(o && o.title); },
  showLoading: () => {},
  hideLoading: () => {},
  showModal: (o) => { if (o.success) o.success({ confirm: true }); },
  navigateTo: (o) => { navigations.push(o.url); if (o.complete) o.complete(); },
  redirectTo: (o) => { navigations.push('redirect:' + o.url); },
  getSystemInfoSync: () => ({ windowWidth: 375 }),
  nextTick: (f) => f(),
  setNavigationBarColor: () => {},
  setNavigationBarTitle: () => {},
};

global.Page = (cfg) => { pageConfig = cfg; };

beforeEach(() => {
  actor = 1;
  pendingStorage = new Map();
  sent = [];
  toasts = [];
  navigations = [];
  pageConfig = null;
  // roleGuard 是模块级单例,在途请求会跨用例粘连(上一条没回包的 flight 让下一条根本不发请求);
  // 连缓存一起重新 require,才测得到「每个用例都从干净的本地身份起步」。
  delete require.cache[require.resolve('../../utils/roleGuard.js')];
  delete require.cache[require.resolve(PAGE)];
  require(PAGE);
});

function makePage() {
  const inst = Object.assign({}, pageConfig);
  inst.data = JSON.parse(JSON.stringify(pageConfig.data));
  inst.setData = function (patch) { Object.assign(inst.data, patch); };
  return inst;
}

function last(url) {
  return sent.filter((r) => r.url === url).pop();
}

function urlsSent() {
  return sent.map((r) => r.url);
}

function merchantAccess(permissions) {
  return {
    code: '200',
    data: {
      active: true,
      merchant: { id: 5, name: '外滩茶室', logo: 'logo.jpg' },
      roleCode: 'MERCHANT_OWNER',
      permissions: permissions,
    },
  };
}

function activityRow(overrides) {
  return Object.assign({
    id: 301,
    bizType: 'activity',
    title: '外滩夜行',
    projectTypeText: '商家活动',
    state: 'pending',
    stateText: '待审核',
    ownerType: 'member',
    signupCount: 0,
    viewCount: 12,
  }, overrides);
}

function loadActivities(page, rows) {
  page.setData({ typeTab: 'activity' });
  page.loadProjects();
  last('/api/project/my').success({ code: '200', data: { rows: rows, total: rows.length } });
}

// ---- 入口判据 --------------------------------------------------------------

test('商家态读岗位权限接口决定入口,不读本地 role 缓存', () => {
  const page = makePage();
  page.onLoad({ scope: 'MERCHANT' });

  assert.ok(last('/api/merchant/access/me'), '必须刷新 /api/merchant/access/me');
  assert.equal(last('/api/role/info'), undefined,
    '商家能不能发看项目管理权，不是全局 role;读缓存会把店长/核销员一视同仁，实际:' + JSON.stringify(urlsSent()));
  assert.equal(page.data.canPublishActivity, false, '回包前 fail-closed,入口先不出');
});

test('店主持「项目管理」→ 出现「发布单场活动」入口', () => {
  const page = makePage();
  page.onLoad({ scope: 'MERCHANT' });
  last('/api/merchant/access/me').success(merchantAccess([PROJECT_MANAGE]));
  assert.equal(page.data.canPublishActivity, true);
});

test('核销员岗(active 但没有项目管理权)→ 不出入口', () => {
  const page = makePage();
  page.onLoad({ scope: 'MERCHANT' });
  last('/api/merchant/access/me').success(merchantAccess(['merchant:verify', 'merchant:order:read']));
  assert.equal(page.data.canPublishActivity, false,
    '「进过商家页」不等于「能发活动」,否则核销员也能代表店铺发出去');
});

test('权限接口读失败 → 入口不出(fail-closed,不拿缓存兜一个 true)', () => {
  const page = makePage();
  page.onLoad({ scope: 'MERCHANT' });
  last('/api/merchant/access/me').fail({ msg: '网络异常' });
  assert.equal(page.data.canPublishActivity, false);
});

test('权限接口断网或 HTTP 异常 → 明确提示读取失败且撤掉旧权限', () => {
  const page = makePage();
  for (const response of [{ errMsg: 'request:fail' }, { msg: '服务暂不可用' }]) {
    page.setData({ canPublishActivity: true });
    page.loadMerchantPublishEntry();
    last('/api/merchant/access/me').fail(response);
    assert.equal(page.data.canPublishActivity, false);
  }
  assert.deepEqual(toasts, ['发布权限没能读取，请重试', '服务暂不可用']);
});

test('回包脏(缺 active/主体)→ 入口不出', () => {
  const page = makePage();
  page.onLoad({ scope: 'MERCHANT' });
  last('/api/merchant/access/me').success({ code: '200', data: { permissions: [PROJECT_MANAGE] } });
  assert.equal(page.data.canPublishActivity, false);
});

test('俱乐部态仍走 roleGuard,不碰商家权限接口(负控)', () => {
  const page = makePage();
  page.onLoad({});
  assert.ok(last('/api/role/info'), '主理人那条路的判据没被换掉');
  assert.equal(last('/api/merchant/access/me'), undefined);
});

// ---- 往下跳转必须带 scope --------------------------------------------------

test('商家态新建活动带 scope=MERCHANT', () => {
  const page = makePage();
  page.onLoad({ scope: 'MERCHANT' });
  last('/api/merchant/access/me').success(merchantAccess([PROJECT_MANAGE]));
  page.goCreateActivity();
  assert.equal(navigations[0], '/pages/publish/activity/index?scope=MERCHANT');
});

test('商家态编辑带 id + scope=MERCHANT', () => {
  const page = makePage();
  page.onLoad({ scope: 'MERCHANT' });
  last('/api/merchant/access/me').success(merchantAccess([PROJECT_MANAGE]));
  loadActivities(page, [activityRow({ state: 'notStarted', stateText: '未开始' })]);

  const card = page.data.list[0];
  assert.equal(card._canEdit, true);
  page.editActivity({ currentTarget: { dataset: { item: card } } });
  assert.equal(navigations[0], '/pages/publish/activity/index?id=301&scope=MERCHANT');
});

test('商家态待审核卡直接进编辑器也要带 scope', () => {
  const page = makePage();
  page.onLoad({ scope: 'MERCHANT' });
  last('/api/merchant/access/me').success(merchantAccess([PROJECT_MANAGE]));
  loadActivities(page, [activityRow({ state: 'pending' })]);

  page.onCardTap({ currentTarget: { dataset: { item: page.data.list[0] } } });
  assert.equal(navigations[0], '/pages/publish/activity/index?id=301&scope=MERCHANT');
});

test('俱乐部态三处跳转都不带 scope(负控:参数串台会被后端当商家主办)', () => {
  const page = makePage();
  page.onLoad({});
  last('/api/role/info').success({ code: '200', data: { role: 'club' } });
  loadActivities(page, [activityRow({ state: 'pending' })]);

  page.goCreateActivity();
  page.editActivity({ currentTarget: { dataset: { item: page.data.list[0] } } });
  page.onCardTap({ currentTarget: { dataset: { item: page.data.list[0] } } });

  assert.deepEqual(navigations, [
    '/pages/publish/activity/index',
    '/pages/publish/activity/index?id=301',
    '/pages/publish/activity/index?id=301',
  ], '实际:' + JSON.stringify(navigations));
});

test('列表请求跟着 scope 走,商家主办的那一份才回得来', () => {
  const page = makePage();
  page.onLoad({ scope: 'MERCHANT' });
  last('/api/merchant/access/me').success(merchantAccess([PROJECT_MANAGE]));
  page.loadProjects();
  assert.equal(last('/api/project/my').data.scope, 'MERCHANT');
});

function projects(request, id) {
  request.success({ code: 200, data: { rows: [
    { id, bizType: 'topic', state: 'draft', title: '回包归属测试' }
  ], total: 1 } });
}

test('old owner response after hiding and changing account must not fill private cards', () => {
  const p=makePage();
  p.onShow();
  const old=last('/api/project/my');
  p.onHide();
  actor=2;
  projects(old,71);
  assert.deepEqual(p.data.list,[]);
});
test('changing account then failed refresh must clear previous owner cards', () => {
  const p=makePage();
  p.onShow();
  projects(last('/api/project/my'),72);
  p.onHide();
  actor=2;
  p.onShow();
  last('/api/project/my').fail({msg:'offline'});
  assert.deepEqual(p.data.list,[]);
  assert.equal(p.data.projectState,'error');
});
test('unloaded page ignores late failure and complete', () => {
  const p=makePage();
  p.onShow();
  const old=last('/api/project/my');
  p.onUnload();
  const before=JSON.parse(JSON.stringify(p.data));
  old.fail({msg:'late error'});
  old.complete();
  assert.deepEqual(p.data,before);
});
test('template late response after hiding must not fill private templates', () => {
  const p=makePage();
  p.data.typeTab='template';
  p.onShow();
  const old=last('/api/template/my-list');
  p.onHide();
  actor=2;
  old.success({code:200,data:{rows:[{id:73,draftStatus:1,status:1,checkStatus:1}]}});
  assert.deepEqual(p.data.tplList,[]);
});
test('returning merchant page must re-read existing publish permission', () => {
  const p=makePage();
  p.onLoad({scope:'MERCHANT'});
  const count=sent.filter(r=>r.url==='/api/merchant/access/me').length;
  p.onHide();
  p.onShow();
  assert.ok(sent.filter(r=>r.url==='/api/merchant/access/me').length>count);
});

function tap(id,biz){return {currentTarget:{dataset:{id,biz,online:true}}};}
test('topic and activity with same numeric ID have independent in-flight guards', () => {
  const p=makePage();
  p.toggleStatus(tap(81,'topic'));
  p.toggleStatus(tap(81,'activity'));
  assert.equal(sent.length,2);
  p.toggleStatus(tap(81,'topic'));
  p.toggleStatus(tap(81,'activity'));
  assert.equal(sent.length,2);
});
test('previous page flight completion cannot release a newer same-card guard', () => {
  const p=makePage();
  p.loadProjects=()=>{};
  p.loadTemplates=()=>{};
  p.toggleStatus(tap(82,'topic'));
  const old=sent[0];
  p.onShow();
  p.toggleStatus(tap(82,'topic'));
  old.complete();
  p.toggleStatus(tap(82,'topic'));
  assert.equal(sent.length,2);
});

test('same owner refresh failure preserves own last successful list', () => {
  const p=makePage();
  p.onLoad({scope:'MERCHANT'});
  p.onShow();
  projects(last('/api/project/my'),91);
  p.onHide();
  p.onShow();
  last('/api/project/my').fail({msg:'offline'});
  assert.equal(p.data.list[0].id,91);
  assert.equal(p.data.projectState,'ready');
});
test('older project completion cannot clear new request loading', () => {
  const p=makePage();
  p.onShow();
  const old=last('/api/project/my');
  p.reload();
  old.complete();
  assert.equal(p.data.loading,true);
  projects(last('/api/project/my'),92);
  last('/api/project/my').complete();
  assert.equal(p.data.loading,false);
  assert.equal(p.data.list[0].id,92);
});
test('old merchant permission reply does not restore entry after account changes', () => {
  const p=makePage();
  p.onLoad({scope:'MERCHANT'});
  const old=last('/api/merchant/access/me');
  p.onHide();
  actor=2;
  p.onShow();
  old.success({code:200,data:{active:true,merchant:{id:5},permissions:['merchant:project:manage']}});
  assert.equal(p.data.canPublishActivity,false);
});
test('new owner template request failure clears old owner templates', () => {
  const p=makePage();
  p.data.typeTab='template';
  p.onShow();
  last('/api/template/my-list').success({code:200,data:{rows:[{id:93,draftStatus:1,status:1,checkStatus:1}]}});
  p.onHide();
  actor=2;
  p.onShow();
  last('/api/template/my-list').fail({msg:'offline'});
  assert.equal(p.data.tplList.length,0);
  assert.equal(p.data.tplLoaded,false);
});


const operationCases = [
  ['topic status', 'toggleStatus', '/api/topic/update_user_status', { currentTarget: { dataset: { id: 91, biz: 'topic', online: true } } }],
  ['activity status', 'toggleStatus', '/api/activity/update_publish_status', { currentTarget: { dataset: { id: 91, biz: 'activity', online: true } } }],
  ['template status', 'tplToggle', '/api/template/updateLibraryStatus', { currentTarget: { dataset: { id: 91, pub: 1 } } }],
  ['market submit', 'tplSubmitMarket', '/api/template/91/market-submit', { currentTarget: { dataset: { id: 91 } } }],
  ['activity refund preview', 'cancelActivity', '/api/activity/cancel_preview', { currentTarget: { dataset: { id: 91 } } }],
  ['topic refund preview', 'cancelTopic', '/api/topic/cancel_preview', { currentTarget: { dataset: { id: 91 } } }],
  ['activity cancel', 'onConfirmCancelActivity', '/api/activity/cancel', { detail: { params: { id: 91 } } }],
  ['topic cancel', 'onConfirmCancelTopic', '/api/topic/cancel', { detail: { params: { id: 91, count: 2, scope: '', cancellationEpoch: '0' } } }],
  ['activity delete', 'onConfirmDeleteActivity', '/api/activity/delete', { detail: { params: { id: 91 } } }],
  ['topic delete', 'onConfirmDeleteRoute', '/api/topic/delete', { detail: { params: { id: 91 } } }],
  ['template delete', 'onConfirmDeleteTemplate', '/api/template/delete', { detail: { params: { id: 91 } } }]
];

function operationPage() {
  const p = makePage();
  const effects = [];
  p._visible = true;
  p._ownerId = String(actor);
  p.data.tplList = [{ id: 91, _canSubmitMarket: true }];
  p.reload = () => effects.push('reload');
  p.loadTemplates = () => effects.push('templates');
  p.selectComponent = () => ({
    open: () => effects.push('open'),
    busyOn: () => effects.push('busy'),
    done: () => effects.push('done'),
    failed: () => effects.push('failed')
  });
  return { p, effects };
}

function deliverOperation(req, outcome) {
  if (outcome === 'fail') req.fail({});
  else {
    const res = outcome === 'success'
      ? { code: 200, data: { paidPlayers: 2, cancellationEpoch: '0', cancellationState: 'DONE',
        refundedOrders: 2, manualOrders: 0, closedPendingOrders: 0 } } : { code: 409, msg: 'rejected' };
    // Mirror the existing request client's automatic business-error toast.
    if (res.code !== 200 && !req.silentError) toasts.push('transport error');
    req.success(res);
  }
  if (req.complete) req.complete();
}

for (const [name, method, url, event] of operationCases) {
  for (const transition of ['owner change', 'hide', 'unload', 'hide and return']) {
    for (const outcome of ['success', 'business error', 'fail']) {
      test(name + ': stale ' + outcome + ' after ' + transition + ' has no UI side effects', () => {
        const { p, effects } = operationPage();
        p[method](event);
        const old = last(url);
        assert.ok(old, 'the real Page handler must send its existing request');
        if (transition === 'owner change') actor = 2;
        else if (transition === 'unload') p.onUnload();
        else {
          p.onHide();
          if (transition === 'hide and return') p.onShow();
        }
        effects.length = 0;
        toasts.length = 0;
        const beforeRequests = sent.length;
        deliverOperation(old, outcome);
        assert.deepEqual(effects, [], 'old operation must not close/open cards or refresh the new page');
        assert.deepEqual(toasts, [], 'old operation must not report a result in a different page context');
        assert.equal(sent.length, beforeRequests);
      });
    }
  }
  for (const outcome of ['success', 'business error', 'fail']) {
    test(name + ': current-owner ' + outcome + ' still reports its result', () => {
      const { p, effects } = operationPage();
      p[method](event);
      effects.length = 0;
      deliverOperation(last(url), outcome);
      assert.ok(effects.length + toasts.length > 0, 'current operation must retain feedback/reload');
    });
  }
}

for (const outcome of ['success', 'fail', 'complete']) {
  test('old template status ' + outcome + ' cannot unlock a newer same-template request', () => {
    const { p } = operationPage();
    const event = { currentTarget: { dataset: { id: 91, pub: 1 } } };
    p.tplToggle(event);
    const old = last('/api/template/updateLibraryStatus');
    p.onHide();
    p.onShow();
    p.tplToggle(event);
    const before = sent.filter(r => r.url === '/api/template/updateLibraryStatus').length;
    if (outcome === 'complete') old.complete();
    else deliverOperation(old, outcome);
    p.tplToggle(event);
    assert.equal(sent.filter(r => r.url === '/api/template/updateLibraryStatus').length, before);
  });
}


test('hiding the project page closes only its five owned confirmation components', () => {
  const p = makePage();
  const closed = [];
  p.selectComponent = id => ({ close: () => closed.push(id) });
  p.onHide();
  assert.deepEqual(closed, ['#dcActivity', '#dcCancel', '#dcCancelTopic', '#dcRoute', '#dcTpl']);
});

test('real danger confirmation does not remain busy or consume an old result after returning', () => {
  let definition;
  const priorComponent = global.Component;
  global.Component = cfg => { definition = cfg; };
  const componentPath = '../../components/cy/danger-confirm/index.js';
  delete require.cache[require.resolve(componentPath)];
  try { require(componentPath); } finally { global.Component = priorComponent; }
  const dc = Object.assign({}, definition.methods);
  dc.data = JSON.parse(JSON.stringify(definition.data));
  dc.setData = patch => { Object.assign(dc.data, patch); };
  dc.triggerEvent = () => {};
  const { p } = operationPage();
  p.selectComponent = id => id === '#dcCancelTopic' ? dc : null;
  p.cancelTopic({ currentTarget: { dataset: { id: 91 } } });
  last('/api/topic/cancel_preview').success({ code: 200, data: { paidPlayers: 2, cancellationEpoch: '0' } });
  p.onConfirmCancelTopic({ detail: { params: dc._params } });
  const old = last('/api/topic/cancel');
  assert.equal(dc.data.busy, true);
  p.onHide();
  assert.equal(dc.data.stage, '', 'the returning page must not retain an unusable busy confirmation');
  assert.equal(dc.data.busy, false);
  assert.equal(dc._params, null);
  p.onShow();
  dc.open('topic.cancel-refund', { id: 99, count: 1 });
  deliverOperation(old, 'success');
  assert.equal(dc.data.stage, 'confirm');
  assert.equal(dc._params.id, 99, 'old completion must not close or replace a new confirmation');
});
