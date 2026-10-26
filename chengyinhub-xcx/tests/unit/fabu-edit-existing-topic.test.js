// [B3b] publish/fabu 编辑既有主题 —— 行为测试,不看源码文本。
//
// 病:项目列表的「草稿 / 未通过」卡、模板页「使用此模板」三个入口都带 ?id= 跳进编辑器,
// 但页面从不读 options.id、保存又只调 /api/topic/create ⇒ 打开是空白表单、保存是再新建一条,
// 原草稿纹丝不动,重复主题越攒越多。整条链路上没有任何报错 —— 只有钉死请求形状才抓得到。
//
// 承重不变量:
//  - 带 id 进入 → 必须先拉 /api/topic/author-edit-detail 回填 formData(名字/章节/票都要落到位)
//  - 保存 → 必须打 /api/topic/update 且 payload 带 id;绝不能再打 /api/topic/create
//  - 不带 id(新建)→ 行为不变,仍打 /api/topic/create 且 payload 不带 id
//  - editScope=WHITELIST(已过审开卖)→ 上送体必须剔掉日期/章节结构/票,只留白名单文案与图
const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');

const PAGE = '../../pages/publish/fabu/index.js';

let sent = [];
let storage = {};
let navigations = [];
let toasts = [];
let modals = [];

global.getApp = () => ({
  globalData: { statusBarHeight: 20, navBarHeight: 44 },
  sendRequest: (p) => { sent.push(p); },
  tips: (m) => { toasts.push(m); },
  getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
  chooseImage: () => {},
  getUserID: () => 7,
  getNickname: () => '测试作者',
  getAvatar: () => '',
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
  inst.data.draftUuid = 'logged-in-test-draft';
  inst.data.draftMemberId = 7;
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

test('俱乐部异步列表到达后立即刷新发布按钮状态', () => {
  const page = makePage();
  page.data.formData.clubId = '';
  page.data.canPublish = true;
  page.refreshPrimaryActionState = function () {
    const needsClub = this.data.myClubs.length > 0;
    this.setData({ canPublish: !needsClub || !!this.data.formData.clubId });
  };

  page.loadMyClubs();
  assert.equal(sent.length, 1);
  sent[0].success({
    code: '200',
    data: { owned: [{ id: 11, status: 1 }, { id: 12, status: 1 }] }
  });

  assert.equal(page.data.myClubs.length, 2);
  assert.equal(page.data.canPublish, false);
})

function editDetailFixture(editScope) {
  return {
    editScope: editScope || 'FULL',
    topic: {
      id: 77,
      name: '午夜巴士',
      subtitle: '一条老线路',
      description: '沿着 71 路走一遍',
      startDate: '2026-09-01',
      endDate: '2026-09-30',
      imgUrl: 'cover.jpg',
      imgArr: 'a.jpg,b.jpg',
      categoryIds: '3,4',
      productType: 1,
      merchantStatus: 1,
      openClubPool: 0,
      publishMode: 'pro',
      selfPlay: 1,
      selfPlayPrice: 9.9,
      selfPlayQuota: 50,
      finishMedalName: '夜行者',
      finishMedalImg: 'https://cdn.example.com/medal-night.png',
      completeRewardCouponId: 12,
    },
    chapters: [{
      id: 5,
      name: '第一章',
      cmsTopicNodeList: [{ id: 51, name: '外滩', address: '中山东一路', nodeTime: 20 }],
    }],
    tickets: [{ id: 9, name: '早鸟票', price: 19.9, totalInventory: 100, startTime: '2026-09-01', endTime: '2026-09-30' }],
    collaboratorIds: [201, 202],
  };
}

/** 带 id 打开编辑器,并把 edit-detail 的响应喂回去。 */
function openForEdit(editScope) {
  const page = makePage();
  page.onLoad({ id: '77' });
  const detailReq = sent.find((r) => r.url === '/api/topic/author-edit-detail');
  assert.ok(detailReq, '带 id 进入必须去拉主题详情回填，否则编辑器就是个空白新建表单');
  assert.equal(detailReq.data.id, '77');
  detailReq.success({ code: '200', data: editDetailFixture(editScope) });
  return page;
}

test('带 id 进入:拉 edit-detail 并把内容回填到 formData', () => {
  const page = openForEdit('FULL');

  assert.equal(page.data.editingTopicId, '77');
  assert.equal(page.data.formData.name, '午夜巴士');
  assert.equal(page.data.formData.subtitle, '一条老线路');
  assert.equal(page.data.formData.startDate, '2026-09-01');
  assert.deepEqual(page.data.selectedCategoryIds, ['3', '4']);
  // 章节的节点在后端叫 cmsTopicNodeList,编辑器叫 nodes —— 这层落差漏了就是「编辑一次丢光路线」
  assert.equal(page.data.formData.chapters.length, 1);
  assert.equal(page.data.formData.chapters[0].nodes.length, 1);
  assert.equal(page.data.formData.chapters[0].nodes[0].name, '外滩');
  // 票的库存字段两边也不同名
  assert.equal(page.data.formData.tickets.length, 1);
  assert.equal(page.data.formData.tickets[0].totalStock, 100);
  // 通关勋章图:漏了回填映射的话,编辑既有主题会静默把已传的勋章图清空 —— 保存后才发现。
  assert.equal(page.data.formData.finishMedalImg, 'https://cdn.example.com/medal-night.png',
    '通关勋章图必须回填，否则编辑主题会静默丢图');
  assert.deepEqual(page.data.formData.collaboratorIds, [201, 202]);
  assert.equal(page.data.formData.openMerchantPool, true, 'merchantStatus=1 → 进商家池');
  assert.equal(page.data.formData.selfPlay, true);
  assert.deepEqual(page.data.imgArrList, ['a.jpg', 'b.jpg']);
});

test('编辑详情拒绝数组对象及畸形章节/票种，保留原表单', () => {
  const malformed = [
    [],
    { topic: {}, chapters: {}, tickets: [], collaboratorIds: [] },
    { topic: {}, chapters: [null], tickets: [], collaboratorIds: [] },
    { topic: {}, chapters: [{ cmsTopicNodeList: [null] }], tickets: [], collaboratorIds: [] },
    { topic: {}, chapters: [], tickets: [null], collaboratorIds: [] },
    { topic: {}, chapters: [], tickets: [], collaboratorIds: [null] },
    { topic: {}, chapters: [], tickets: [], collaboratorIds: [{}] },
    { topic: {}, chapters: [], tickets: [], collaboratorIds: ['not-an-id'] },
  ];
  for (const data of malformed) {
    const page = makePage();
    page.data.formData.name = '原主题';
    page.loadEditingTopic('77');
    const request = sent.at(-1);
    assert.doesNotThrow(() => request.success({ code: '200', data }));
    assert.equal(page.data.editLoaded, false);
    assert.equal(page.data.formData.name, '原主题');
    assert.match(page.data.editLoadError, /主题|打开|重试/);
    assert.deepEqual(toasts, [], '畸形 payload 应留在可重试错误态，不应只弹 toast');
  }
});

test('节点玩法选择器拒绝非数组和空元素，不生成可点击伪选项', () => {
  for (const rows of [{}, [null]]) {
    const page = makePage();
    page.getTempList();
    const request = sent.at(-1);
    assert.doesNotThrow(() => request.success({ code: '200', data: { rows } }));
    assert.deepEqual(page.data.tempList, []);
  }
});

test('保存:走 /api/topic/v2/update 且带 id,绝不再打 create', () => {
  const page = openForEdit('FULL');
  sent = [];

  page._doSubmit();

  const req = sent.find((r) => r.url === '/api/topic/v2/update');
  assert.ok(req, '编辑保存必须打 update;打 create 就是原 bug —— 每保存一次多一条重复主题');
  assert.equal(sent.filter((r) => r.url === '/api/topic/v2/create').length, 0);
  const body = JSON.parse(req.data);
  assert.equal(body.id, '77', 'update 不带 id 后端不知道改谁');
  assert.equal(body.name, '午夜巴士');
  assert.equal(body.chapters.length, 1, 'FULL 档要整包上送，章节结构可改');
});

test('不带 id(新建):专业故事走 V2 create 且不带 id', () => {
  const page = makePage();
  page.onLoad({});

  assert.equal(page.data.editingTopicId, '');
  assert.equal(sent.filter((r) => r.url === '/api/topic/author-edit-detail').length, 0,
    '新建不该去拉详情');
  sent = [];
  page._doSubmit();

  const req = sent.find((r) => r.url === '/api/topic/v2/create');
  assert.ok(req, '专业故事必须走 V2 create');
  assert.equal(JSON.parse(req.data).id, undefined);
});

test('editScope=WHITELIST:上送体只剩白名单文案与图,日期/章节/票被剔掉', () => {
  const page = openForEdit('WHITELIST');
  sent = [];

  page._doSubmit();

  const req = sent.find((r) => r.url === '/api/topic/update');
  assert.ok(req);
  const body = JSON.parse(req.data);
  // 能改的
  assert.equal(body.name, '午夜巴士');
  assert.equal(body.imgArr, 'a.jpg,b.jpg');
  assert.equal(body.categoryIds, '3,4');
  // 不能改的:已开卖的主题改这三样是资损。后端会当场拒收,前端先剔掉免得每次保存都撞
  assert.equal(body.startDate, undefined, '已开卖不得上送开始日期');
  assert.equal(body.endDate, undefined, '已开卖不得上送结束日期');
  assert.equal(body.chapters, undefined, '已开卖不得上送章节站点结构');
  assert.equal(body.tickets, undefined, '已开卖不得上送票种与价格');
});

test('WHITELIST 档改了票价:必须当场说清楚,不许静默剔掉后报「已保存」', () => {
  const page = openForEdit('WHITELIST');
  // 用户在编辑器里把票价改了
  page.data.formData.tickets[0].price = 999;
  sent = [];
  modals = [];

  page._doSubmit();

  assert.equal(sent.length, 0, '改了锁定字段就不该发请求 —— 发了就等于「保存成功但没生效」');
  assert.equal(modals.length, 1, '必须给一条可识别的拒绝信息，而不是默默吞掉');
  assert.ok(/票种与价格|不能再改/.test(modals[0].content), modals[0].content);
});

test('WHITELIST 档只改文案:照常保存', () => {
  const page = openForEdit('WHITELIST');
  page.data.formData.name = '午夜巴士(改名版)';
  sent = [];
  modals = [];

  page._doSubmit();

  assert.equal(modals.length, 0, '只改文案不该被拦');
  const req = sent.find((r) => r.url === '/api/topic/update');
  assert.ok(req);
  assert.equal(JSON.parse(req.data).name, '午夜巴士(改名版)');
});

test('回填还没到位就点保存:拦住,别拿空表单去重建', () => {
  const page = makePage();
  page.onLoad({ id: '77' });   // 详情请求在途,success 没回来
  sent = [];

  page.submitForm();

  assert.equal(sent.length, 0, 'FULL 档保存会整体重建章节/票，空表单存下去等于把原主题删空');
  assert.ok(toasts.some((t) => /加载/.test(String(t))), '要告诉用户为什么点不动:' + JSON.stringify(toasts));
});

test('主题初始状态通过手机表单保存，编辑回填及 V2 提交保持一致', () => {
  const page = openForEdit('FULL');
  page.openInitialState();
  page.addInitialState({ currentTarget: { dataset: { type: 'hp' } } });
  page.onInitialStateField({ currentTarget: { dataset: { field: 'hp.init' } }, detail: { value: '8' } });
  page.saveInitialState();
  const saved = page.data.formData.journeyRules;
  assert.equal(JSON.parse(saved).hp.init, 8);
  assert.equal(JSON.parse(saved).hp.enabled, true);
  sent = [];
  page._doSubmit();
  const request = sent.find(r => r.url === '/api/topic/v2/update');
  assert.equal(JSON.parse(request.data).journeyRules, saved);
  request.success({ code: 200, data: { topicId: 77, published: false, reviewState: 'PENDING' } });
  assert.equal(page.data.resultSheet.title, '已提交审核');
  assert.doesNotMatch(page.data.resultSheet.sub, /已经发布/);
});

test('V2 创建回执从 topicId 导航，不把对象写进 URL', () => {
  const page = makePage();
  page.onLoad({});
  page._doSubmit();
  sent.find(r => r.url === '/api/topic/v2/create').success({ code: 200, data: { topicId: 123, published: false, reviewState: 'PENDING' } });
  assert.match(page._resultSheetNext, /topicId=123/);
  assert.doesNotMatch(page._resultSheetNext, /object/);
  assert.equal(page.data.resultSheet.title, '已提交审核');
});

test('自由探索编辑伏笔后随原主题保存，重开保留资源和应验条件', () => {
  const page = openForEdit('FULL');
  page.data.formData.productType = 2;
  page.data.formData.journeyRules = JSON.stringify({
    schemaVersion: 1,
    hp: { enabled: true, init: 10, max: 10 },
    luck: { enabled: true, init: 3, max: 3 },
    thoughts: [{ key: 'window', name: '那扇窗', need: 120,
      when: [{ op: 'HAS_TAG', value: 'tag.saw_window' }],
      doneWhen: [{ op: 'HAS_TAG', value: 'tag.opened_window' }] }]
  });
  page.openThoughts();
  page.onThoughtInput({ currentTarget: { dataset: { index: 0, field: 'desc' } }, detail: { value: '窗后的回声' } });
  page.saveThoughts();
  assert.equal(page.data.thoughtsEditor.show, false);
  assert.equal(page.data.thoughtsEditor.count, 1);
  sent = [];
  page._doSubmit();
  const request = sent.find(r => r.url === '/api/topic/v2/update');
  assert.ok(request, '普通自由探索编辑伏笔保存原主题，不新建主题');
  const payload = JSON.parse(request.data);
  assert.equal(payload.id, '77');
  assert.equal(payload.productType, 2);
  const rules = JSON.parse(payload.journeyRules);
  assert.equal(rules.thoughts[0].name, '那扇窗');
  assert.equal(rules.thoughts[0].desc, '窗后的回声');
  assert.equal(rules.thoughts[0].key, 'window');
  assert.deepEqual(rules.thoughts[0].doneWhen, [{ op: 'HAS_TAG', value: 'tag.opened_window' }]);
  assert.equal(rules.hp.init, 10);
  page.data.formData.journeyRules = payload.journeyRules;
  page.openThoughts();
  assert.equal(page.data.thoughtsEditor.list[0].name, '那扇窗');
  assert.equal(page.data.thoughtsEditor.list[0].desc, '窗后的回声');
  assert.deepEqual(page.data.thoughtsEditor.list[0].doneWhen, [{ op: 'HAS_TAG', value: 'tag.opened_window' }]);
});

test('普通自由探索的章节故事流保存走 V2，保留正文、节点引用和计酬条款', () => {
  for (const editingId of ['77', '']) {
    storage = {}; // Independent create/update fixtures must not restore the previous in-flight write.
    const page = openForEdit('FULL');
    page.data.editingTopicId = editingId;
    page.data.formData.productType = 2;
    const chapter = page.data.formData.chapters[0];
    chapter.blocks = [
      { key: 'intro', type: 'text', content: '保存后仍须显示这段剧情' },
      { key: 'game', type: 'node', nodeKey: chapter.nodes[0]._localId, locationRequired: false }
    ];
    chapter.termsMode = 'REVSHARE';
    chapter.maxPerHeadFee = 0.02;
    sent = [];
    page._doSubmit();
    const endpoint = editingId ? '/api/topic/v2/update' : '/api/topic/v2/create';
    const request = sent.find(r => r.url === endpoint);
    assert.ok(request, '有故事流的普通自由探索不能走旧接口');
    const payload = JSON.parse(request.data);
    assert.equal(payload.chapters[0].blocks[0].content, '保存后仍须显示这段剧情');
    assert.equal(payload.chapters[0].blocks[1].nodeIndex, 0);
    assert.equal(payload.chapters[0].blocks[1].locationRequired, false);
    assert.equal(payload.chapters[0].termsMode, 'REVSHARE');
    assert.equal(payload.chapters[0].maxPerHeadFee, 0.02);
    assert.equal(payload.id, editingId || undefined);
  }
});

test('普通自由探索服务器回填还原无需地点的故事玩法，保留故事块位置', () => {
  const page = makePage();
  page.data.editingTopicId = '77';
  const detail = editDetailFixture('FULL');
  detail.topic.productType = 2;
  detail.chapters[0].cmsTopicNodeList[0].templateId = 71;
  detail.chapters[0].cmsTopicNodeList[0].address = null;
  detail.chapters[0].blocks = [
    { key: 'intro', type: 'text', content: '先阅读剧情' },
    { key: 'game', type: 'node', nodeId: 51, locationRequired: false }
  ];
  page.applyEditingTopic(detail);
  const chapter = page.data.formData.chapters[0];
  assert.equal(chapter.nodes[0]._storyGame, true, '重开不能把无需地点玩法误判成缺坐标的站点');
  assert.equal(chapter.blocks[1].nodeKey, chapter.nodes[0]._localId);
  assert.equal(chapter.blocks[1].locationRequired, false);
  assert.equal(chapter.blocks[0].content, '先阅读剧情');
  assert.equal(page.data.chapterStates[0].nodesMissingCoords, 0);
});

test('初始状态打开时物理返回只关闭弹层', () => {
  const page = makePage();
  page.openInitialState();
  const originalRules = page.data.formData.journeyRules;
  page.onNativeBackGuard();
  assert.equal(page.data.initialStateEditor.show, false);
  assert.equal(page.data.formData.journeyRules, originalRules);
  assert.deepEqual(navigations, []);
});


test('章节开场预览物理返回保留编辑器与章节草稿', () => {
  const page = makePage();
  page.data.formData.chapters = [{ name: '第一章' }];
  page.previewChapterOpening({ currentTarget: { dataset: { index: 0 } } });
  assert.equal(page.data.chapterPreview.show, true);
  page.onNativeBackGuard();
  assert.equal(page.data.chapterPreview.show, false);
  assert.equal(page.data.formData.chapters[0].name, '第一章');
  assert.deepEqual(navigations, []);
});
