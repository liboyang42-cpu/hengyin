// H2 主题自玩单下单幂等（行为测试，不看源码文本）。
//
// 病：_createSelfPlayOrder 既不带 requestId、也没有在途防重 ⇒ 连点两下"去支付"就是两次
// /api/registration/create，后端对空 requestId 直接跳过 replay 探测 ⇒ 两张待支付通行证。
// 这里把页面真实装进沙箱跑它的方法，断言实际发出的请求，而不是断言源码里有没有某个字符串。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

const PAGE_PATH = path.resolve(__dirname, '../../pages/topic/index/index.js');

function loadTopicPage() {
  const requests = [];
  const toasts = [];
  const navigations = [];
  const payments = [];
  const subscriptions = [];
  let pageConfig = null;

  // wx 在方法调用时才查全局，所以它必须留到测试跑完；getApp/Page 只在 require 期间需要。
  const previous = {
    getApp: global.getApp,
    Page: global.Page
  };

  const consents = [];
  const modals = [];
  const modalControl = { confirm: true };
  const consentControl = { reject: false };
  global.getApp = () => ({
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    getUserType: () => 1,
    getUserID: () => 500,
    tips: message => toasts.push(message),
    sendRequest: options => { requests.push(options); },
    recordConsent: input => {
      consents.push(input);
      return consentControl.reject ? Promise.reject(new Error('consent down')) : Promise.resolve({ code: 200 });
    }
  });
  global.Page = config => { pageConfig = config; };
  global.wx = {
    showLoading: () => {},
    hideLoading: () => {},
    showToast: options => toasts.push(options.title),
    showModal: options => {
      modals.push(options);
      if (options.success) options.success({ confirm: modalControl.confirm });
    },
    requestPayment: options => { payments.push(options); },
    requestSubscribeMessage: options => { subscriptions.push(options); },
    navigateTo: options => { navigations.push(options.url); },
    redirectTo: options => { navigations.push(options.url); },
    getSystemInfo: () => {},
    getStorageSync: () => '',
    setStorageSync: () => {},
    createSelectorQuery: () => { const q = { in: () => q, select: () => q, selectAll: () => q, boundingClientRect: () => q, scrollOffset: () => q, exec: () => {} }; return q; }
  };

  delete require.cache[PAGE_PATH];
  try {
    require(PAGE_PATH);
  } finally {
    global.getApp = previous.getApp;
    global.Page = previous.Page;
  }

  const vm = Object.assign({}, pageConfig.data, pageConfig.methods || {}, pageConfig, {
    setData(patch) { Object.assign(this.data, patch); }
  });
  vm.data = Object.assign({}, pageConfig.data);
  return { vm, requests, toasts, navigations, payments, consents, consentControl, modals, modalControl, subscriptions };
}

// 确保每个 page 加载后 workflow 已初始化，否则 _createSelfPlayOrder 里的 workflow.submit 会报错
function loadTopicPageWithWorkflow() {
  const ctx = loadTopicPage();
  if (ctx.vm._initWorkflow) ctx.vm._initWorkflow.call(ctx.vm);
  // RUN-41：建单要带真实姓名+手机号（后端 RegistrationRequest 上 realName/phone 是 @NotBlank）。
  // 幂等/同意这些用例关心的是「发几次单、什么时候发」，资料是否齐不该干扰它们，所以预置成齐。
  // 缓存走实例字段 _signupProfile 而不是 data —— 本页 wxml 不渲染它（U4 A2 会判红）。
  ctx.vm._signupProfile = { realName: '张三', phone: '13800001111' };
  return ctx;
}

function createResponse(overrides) {
  return Object.assign({ code: '200', data: { registrationId: 1001, payableAmount: 100 } }, overrides);
}

function paidOrder() {
  return { registrationId: 1001, payableAmount: 100,
    payParams: { timeStamp: '1720000000', nonceStr: 'nonce', package: 'prepay_id=x', signType: 'RSA', paySign: 'signature' } };
}

test('自玩下单必须带幂等键 requestId，后端才可能识别重放', () => {
  const { vm, requests } = loadTopicPageWithWorkflow();

  vm._createSelfPlayOrder.call(vm, 500);

  assert.equal(requests.length, 1);
  const body = JSON.parse(requests[0].data);
  assert.equal(body.ownerType, 1);
  assert.equal(body.ownerId, 500);
  assert.ok(body.requestId, '缺 requestId ⇒ 后端 findReplay 直接返回 null，重放探测完全不生效');
});

test('在途重复点击只发一次建单请求，不叠第二张待支付通行证', () => {
  const { vm, requests } = loadTopicPageWithWorkflow();

  vm._createSelfPlayOrder.call(vm, 500);
  vm._createSelfPlayOrder.call(vm, 500);

  assert.equal(requests.length, 1, '第二次点击在途期间必须被忽略');
});

test('失败后重试复用同一个幂等键，不会变成两笔独立订单', () => {
  const { vm, requests } = loadTopicPageWithWorkflow();

  vm._createSelfPlayOrder.call(vm, 500);
  requests[0].fail();
  vm._createSelfPlayOrder.call(vm, 500);

  assert.equal(requests.length, 2);
  const firstKey = JSON.parse(requests[0].data).requestId;
  assert.ok(firstKey, '没有幂等键就谈不上复用');
  assert.equal(firstKey, JSON.parse(requests[1].data).requestId,
    '同一次购买意图的重试必须复用同一个 requestId');
});

test('重放命中已有订单（无 payParams）走取支付参数接口，而不是报"支付参数异常"', () => {
  const { vm, requests, toasts } = loadTopicPageWithWorkflow();

  vm._createSelfPlayOrder.call(vm, 500);
  // 工作流内部的 createOrder 会看到无 payParams 但有 registrationId(重放),自动走 /api/registration/pay 取支付参数
  requests[0].success(createResponse({ data: { registrationId: 1001, payableAmount: 100 } }));

  assert.equal(requests.length, 2, '重放返回的已有订单必须继续拿支付参数');
  assert.equal(requests[1].url, '/api/registration/pay');
  assert.deepEqual(toasts, [], '正常重放路径不该弹错误提示');
});

test('零元通行证仍直接进入游玩，付费单仍拉起微信支付', () => {
  const paid = loadTopicPageWithWorkflow();
  paid.vm._createSelfPlayOrder.call(paid.vm, 500);
  paid.requests[0].success(createResponse({
    data: paidOrder()
  }));
  // 工作流通过 requestPayment 依赖拉起微信支付,请求已进入 payments 列表
  assert.equal(paid.payments.length, 1, '有 payParams 必须照常拉起支付');

  const free = loadTopicPageWithWorkflow();
  free.vm._createSelfPlayOrder.call(free.vm, 500);
  free.requests[0].success(createResponse({ data: { registrationId: 1002, payableAmount: 0 } }));
  /* 2026-09-05:购买成功的落点从 toast 换成了 cy-result-sheet(_goSelfPlay 是三个
     调用点共用的漏斗,只改这一处)。被保护的事实不变 —— 零元单也要照常走「购买成功」
     这个落点,不能因为不用付款就悄悄跳过反馈。断言随之改钉面板。 */
  /* 2026-10-22 业主:购买成功的落点再换成「通行证已到账」动效层(cy-pass-reveal),
     买完先看到通行证,再由「放进票夹 / 开始游戏」决定去向;可选的商家营销同意行改在动效收尾时
     走原结果面板。被保护的事实仍不变 —— 零元单同样要走这个成功落点,不能悄悄跳过反馈。 */
  assert.deepEqual(free.toasts, [], '不再额外弹 toast,结果由到账层承担');
  assert.ok(free.vm.data.reveal.show, '零元单照常走购买成功落点:到账层必须真的弹出来');
  assert.equal(free.vm._revealRegId, 1002, '「开始游戏」要能带着这张票直接开局');
  assert.equal(free.payments.length, 0);
  assert.equal(free.requests.length, 1, '零元单不该再去取支付参数');
});

test('取消微信支付不得显示通行证到账或跳票夹', () => {
  const h = loadTopicPageWithWorkflow();
  h.vm._createSelfPlayOrder(500);
  h.requests[0].success(createResponse({ data: paidOrder() }));
  h.payments[0].fail({ errMsg: 'requestPayment:fail cancel' });
  assert.equal(h.vm.data.reveal.show, false);
  assert.equal(h.vm.data.is_join, 0);
  assert.deepEqual(h.navigations, []);
  assert.ok(h.toasts.includes('已取消支付'));
});

test('微信回调成功仍等服务端确认，确认后才显示到账', () => {
  const h = loadTopicPageWithWorkflow();
  h.vm._createSelfPlayOrder(500);
  h.requests[0].success(createResponse({ data: paidOrder() }));
  h.payments[0].success();
  assert.equal(h.vm.data.reveal.show, false);
  assert.deepEqual(h.navigations, []);
  const verify = h.requests.find(r => r.url === '/api/registration/info');
  verify.success({ code: 200, data: { paymentStatus: 2, registrationStatus: 2 } });
  assert.equal(h.vm.data.reveal.show, true);
  assert.equal(h.vm._revealRegId, 1001);
});

test('下单金额缺失或非法时不得当免费成功，也不调起支付', () => {
  for (const payableAmount of [undefined, null, '', ' ', false, [], -1, 'invalid']) {
    const h = loadTopicPageWithWorkflow();
    h.vm._createSelfPlayOrder(500);
    h.requests[0].success(createResponse({ data: Object.assign(paidOrder(), { payableAmount }) }));
    assert.equal(h.vm.data.reveal.show, false);
    assert.equal(h.vm.data.is_join, 0);
    assert.equal(h.payments.length, 0);
    assert.deepEqual(h.navigations, []);
  }
});

test('重试回包有支付参数但缺金额时仍必须付款，取消不能发票', () => {
  const h = loadTopicPageWithWorkflow();
  h.vm._createSelfPlayOrder(500);
  h.requests[0].success(createResponse());
  const order = paidOrder();
  delete order.payableAmount;
  h.requests[1].success(createResponse({ data: order }));
  assert.equal(h.payments.length, 1);
  assert.equal(h.vm.data.reveal.show, false);
  h.payments[0].fail({ errMsg: 'requestPayment:fail cancel' });
  assert.equal(h.vm.data.reveal.show, false);
  assert.deepEqual(h.navigations, []);
});

test('后端业务错误按原合同提示，并允许换一次新的幂等键重来', () => {
  const { vm, requests, toasts } = loadTopicPageWithWorkflow();

  vm._createSelfPlayOrder.call(vm, 500);
  requests[0].success({ code: '500', msg: '你已持有该主题的自玩通行证,可直接开玩' });

  assert.deepEqual(toasts, ['你已持有该主题的自玩通行证,可直接开玩']);
  vm._createSelfPlayOrder.call(vm, 500);
  assert.equal(requests.length, 2, '业务错误后必须能重新下单，不能被在途标记卡死');
});

// P1(2026-09-18 实走查 RUN-41):data.userInfo 从声明到使用之间没有任何一处赋值,自玩建单恒以空姓名空号
// 提交,后端 RegistrationRequest 的 @NotBlank 直接拒 ⇒ 现网 6 个在架自玩主题一个都买不成。
test('进入主题页会把报名资料取回来缓存,建单载荷带真实姓名手机号而不是空串', () => {
  const { vm, requests } = loadTopicPageWithWorkflow();
  vm._signupProfile = undefined;   // 页面初始态：资料还没取回来

  vm._loadSignupProfile.call(vm);
  const profile = requests.find(r => r.url === '/api/user/info');
  assert.ok(profile, '必须打 /api/user/info');
  assert.equal(profile.data.member_id, 500);
  assert.equal(requests.filter(r => r.url === '/api/registration/create').length, 0,
    '取资料不该顺手建单');
  profile.success({ code: '200', data: { name: '张三', mobilePhone: '13800001111' } });

  vm._createSelfPlayOrder.call(vm, 500);
  const create = requests.find(r => r.url === '/api/registration/create');
  assert.ok(create, '资料补齐后必须能建单');
  const body = JSON.parse(create.data);
  assert.equal(body.realName, '张三');
  assert.equal(body.phone, '13800001111');
});

test('资料不全时不打必被拒的建单，给出出路并放开在途标记', () => {
  const { vm, requests, modals, navigations, modalControl } = loadTopicPageWithWorkflow();
  modalControl.confirm = false;
  vm._signupProfile = undefined;

  vm._createSelfPlayOrder.call(vm, 500);
  // 建单前会先现取一次资料(预取可能还没回来)，那次也取不到才给提示
  const retry = requests[requests.length - 1];
  assert.equal(retry.url, '/api/user/info');
  retry.fail();

  assert.equal(requests.filter(r => r.url === '/api/registration/create').length, 0,
    '空姓名建单必被后端拒，不该让玩家看到那句跟自己无关的报错');
  assert.equal(modals.length, 1, '必须给可见提示');
  assert.equal(modals[0].confirmText, '去补全');
  assert.deepEqual(navigations, [], '玩家选「稍后再说」就不该被丢到资料页');
  assert.equal(vm._selfPlaySubmitting, false, '提示后要在途标记放掉，否则玩家补完资料也点不动');

  // 补完资料后同一次页面会话要能继续下单
  vm._signupProfile = { realName: '李四', phone: '13900002222' };
  vm._createSelfPlayOrder.call(vm, 500);
  const create = requests.find(r => r.url === '/api/registration/create');
  assert.ok(create, '资料补上后必须放行');
  assert.equal(JSON.parse(create.data).realName, '李四');
});

test('预取没回来时，点购买现取到资料就直接建单，不误报「去补全」', () => {
  const { vm, requests, modals } = loadTopicPageWithWorkflow();
  vm._signupProfile = undefined;

  vm._createSelfPlayOrder.call(vm, 500);
  const retry = requests[requests.length - 1];
  retry.success({ code: '200', data: { name: '王五', phone: '13700003333' } });

  const create = requests.find(r => r.url === '/api/registration/create');
  assert.ok(create, '资料其实全的人不该被推去补一个已经填好的字段');
  assert.equal(JSON.parse(create.data).realName, '王五');
  assert.deepEqual(modals, [], '现取成功就不该弹补全提示');
});

test('提示里确认「去补全」要把玩家送到资料页', () => {
  const { vm, requests, navigations, modalControl } = loadTopicPageWithWorkflow();
  modalControl.confirm = true;
  vm._signupProfile = undefined;

  vm._createSelfPlayOrder.call(vm, 500);
  requests[requests.length - 1].fail();

  assert.equal(requests.filter(r => r.url === '/api/registration/create').length, 0);
  assert.deepEqual(navigations, ['/pages/gerenziliao/gerenziliao']);
});

// 取资料必须是页面自己喂的,否则又是一条「生了没人喂」的死方法。
test('onLoad 会把取资料接上,不是只在测试里被调用', () => {
  const { vm, requests } = loadTopicPage();
  vm.onLoad.call(vm, { id: 500 });
  assert.ok(requests.some(r => r.url === '/api/user/info'),
    'onLoad 没接 _loadSignupProfile ⇒ 建单永远拿着空资料');
});

// P0(2026-09-05 审核):后端 /create 与 /pay 硬校验「报名信息单独同意」已落库,主题页自玩链路此前零 recordConsent,
// 自玩单永远被拒。以下断言:未勾选不建单;勾选后必须先等 recordConsent 成功再发 /create;同意落库失败不建单。
test('自玩购买:不勾选单独同意不建单,只给内联提示', () => {
  const { vm, requests, consents } = loadTopicPageWithWorkflow();
  vm.data.info = { id: 500 };
  vm.selfPlayBuy.call(vm);
  assert.equal(vm.data.selfPlayConfirmShow, true, '点购买应打开 P 类确认弹层而不是系统 showModal');
  assert.equal(vm.data.selfPlayConsentChecked, false, '单独同意不得默认勾选');
  vm.onSelfPlayConfirm.call(vm);
  assert.equal(requests.length, 0);
  assert.equal(consents.length, 0);
  assert.ok(vm.data.selfPlayConsentHint, '未勾选必须给可见提示');
  assert.equal(vm.data.selfPlayConsentError, '', '未勾选是提示不是错误态，按钮也不得代勾');
  assert.equal(vm.data.selfPlayConsentChecked, false);
});

test('自玩购买:勾选后先落库同意(activity_host_data_sharing/activity_signup),成功才发 /create', async () => {
  const { vm, requests, consents } = loadTopicPageWithWorkflow();
  vm.data.info = { id: 500 };
  vm.selfPlayBuy.call(vm);
  vm.onSelfPlayConsentChange.call(vm, { detail: { checked: true } });
  vm.onSelfPlayConfirm.call(vm);
  assert.equal(requests.length, 0, '同意未落库前不得建单(弱网竞态会被后端拒)');
  assert.deepEqual(consents, [{ docType: 'activity_host_data_sharing', scene: 'activity_signup', eventType: 'AGREE' }]);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, '/api/registration/create');
  assert.equal(vm.data.selfPlayConfirmShow, false);
});

test('自玩购买:同意落库失败则不建单并保留弹层给重试', async () => {
  const { vm, requests, consentControl } = loadTopicPageWithWorkflow();
  consentControl.reject = true;
  vm.data.info = { id: 500 };
  vm.selfPlayBuy.call(vm);
  vm.onSelfPlayConsentChange.call(vm, { detail: { checked: true } });
  vm.onSelfPlayConfirm.call(vm);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(requests.length, 0);
  assert.equal(vm.data.selfPlayConfirmShow, true);
  assert.ok(vm.data.selfPlayConsentError);
});

// P0(2026-09-05 审核):已购态真值来自 /info-to-user 的 isSignUp,而不是只信 URL 的 is_join。
test('已购态:/info-to-user 回 isSignUp=1 ⇒ is_join=1(不依赖 URL 参数);isSignUp=0 保持原态', () => {
  const { vm, requests } = loadTopicPageWithWorkflow();
  vm.data.id = 500; vm.data.is_join = 0;
  vm.getData.call(vm);
  const info = requests.find(r => r.url === '/api/topic/info-to-user');
  assert.ok(info, 'getData 必须打 /api/topic/info-to-user');
  const base = { id: 500, name: 't', chaptersList: [], omsTicketList: [], activityList: [], startDate: '', endDate: '' };
  info.success({ code: '200', data: Object.assign({}, base, { isSignUp: 0 }) });
  assert.equal(String(vm.data.is_join), '0');
  info.success({ code: '200', data: Object.assign({}, base, { isSignUp: 1 }) });
  assert.equal(String(vm.data.is_join), '1');
});


test('主题确认点击请求三种真实订阅模板，不等待订阅回包才记录同意建单', async () => {
  const h=loadTopicPageWithWorkflow();h.vm.data.info={id:500};h.vm.data.selfPlayConsentChecked=true;
  h.vm.onSelfPlayConfirm();assert.equal(h.subscriptions.length,1);
  const {TEMPLATES}=require('../../utils/subscribe');
  assert.deepEqual(h.subscriptions[0].tmplIds,[TEMPLATES.signupSuccess,TEMPLATES.activityStart,TEMPLATES.refund]);
  assert.equal(h.consents.length,1);await Promise.resolve();assert.equal(h.requests.length,1,'订阅不回包也不阻塞建单');
});
test('主题订阅拒绝或API失败不阻塞建单；未勾同意不请求订阅', async () => {
  for(const failed of [false,true]) {
    const h=loadTopicPageWithWorkflow();h.vm.data.info={id:500};h.vm.data.selfPlayConsentChecked=true;
    h.vm.onSelfPlayConfirm();if(failed)h.subscriptions[0].fail({});else h.subscriptions[0].success({});
    await Promise.resolve();assert.equal(h.requests.length,1);
  }
  const h=loadTopicPageWithWorkflow();h.vm.data.info={id:500};h.vm.data.selfPlayConsentChecked=false;
  h.vm.onSelfPlayConfirm();assert.equal(h.subscriptions.length,0);assert.equal(h.requests.length,0);
});


test('已有同意的连续确认也只请求一次订阅与建单', () => {
  const h=loadTopicPageWithWorkflow();h.vm.data.info={id:500};h.vm.data.selfPlayConsentChecked=true;h.vm._hostShareConsentReady=true;
  h.vm.onSelfPlayConfirm();h.vm.onSelfPlayConfirm();assert.equal(h.subscriptions.length,1);assert.equal(h.requests.length,1);
});
