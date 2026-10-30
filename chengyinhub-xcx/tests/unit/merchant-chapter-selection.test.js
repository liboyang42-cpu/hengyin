const { beforeEach, test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { attachDangerConfirm } = require('./helpers/danger-confirm-stub')

const PAGE = '../../pages/topic/merchantinfo/merchantinfo.js';
const WXML = fs.readFileSync(path.resolve(__dirname, '../../pages/topic/merchantinfo/merchantinfo.wxml'), 'utf8');
const PICKER_WXML = fs.readFileSync(path.resolve(
  __dirname,
  '../../pages/topic/components/cy/chapter-target-picker/index.wxml',
), 'utf8');
const CMS_TOPIC_NODE = fs.readFileSync(path.resolve(
  __dirname,
  '../../../chengyinhub-system/src/main/java/com/chengyinhub/business/domain/CmsTopicNode.java',
), 'utf8');

let pageConfig;
let navigations;
let toasts;
let tips;
let requests;
let modals;

global.getApp = () => ({
  globalData: { statusBarHeight: 20, navBarHeight: 44 },
  getUserID: () => 1,
  sendRequest: (request) => requests.push(request),
  tips: (message) => tips.push(message),
  getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
});
global.wx = {
  navigateTo: (options) => navigations.push(options),
  showToast: (options) => toasts.push(options),
  showLoading: () => {},
  hideLoading: () => {},
  navigateBack: () => {},
  openLocation: () => {},
  pageScrollTo: () => {},
  showModal: (options) => modals.push(options),
};
global.Page = (config) => { pageConfig = config; };

beforeEach(() => {
  navigations = [];
  toasts = [];
  tips = [];
  requests = [];
  modals = [];
  pageConfig = null;
  delete require.cache[require.resolve(PAGE)];
  require(PAGE);
});

function makePage() {
  const page = Object.assign({}, pageConfig);
  page.data = JSON.parse(JSON.stringify(pageConfig.data));
  page.setData = (patch) => Object.assign(page.data, patch);
  return page;
}

function requestByUrl(url) {
  return requests.find(request => request.url === url);
}

test('章节邀请撤回必须由危险确认发送，取消确认不提交', () => {
  const page = makePage();
  const dc = attachDangerConfirm(page);
  page.revokeChapterInvitation({ currentTarget: { dataset: { id: 71 } } });
  assert.equal(requests.length, 0);
  assert.equal(dc.opens[0].key, 'merchant.chapterInvitation.revoke');
  dc.cancel();
  assert.equal(requests.length, 0);
  dc.confirm();
  const request = requestByUrl('/api/merchant/chapter-application/invite/revoke');
  assert.equal(JSON.parse(request.data).applicationId, 71);
  request.fail();
  assert.ok(dc.failedCalls.length);
});

test('承接报名仅有详情和任务向导，章节内容保持顺序且不重复描述', () => {
  const page = makePage();
  assert.deepEqual(page.data.browseTabs.map(tab => tab.label), ['详情', '任务向导']);
  const blocks = page.buildGuideBlocks({ id: 2, description: '旧描述不要重复',
    nodes: [{ id: 8, name: '真实地点', imgUrl: 'photo-a;photo-b', description: '到店说明' }],
    blocks: [{ type: 'text', content: '开章' }, { type: 'image', url: 'chapter-photo' },
      { type: 'node', nodeId: 404 }, { type: 'node', nodeId: 8 }, { type: 'text', content: '下一段' }],
  });
  assert.deepEqual(blocks.map(block => block.type), ['text', 'image', 'node', 'text']);
  assert.deepEqual(blocks[2].images, ['photo-a', 'photo-b']);
  assert.equal(blocks[2].step, 1);
  assert.equal(blocks[2].title, '真实地点');
  assert.equal(blocks.filter(block => block.content === '旧描述不要重复').length, 0);
  page.switchBrowseTab({ detail: { key: 'guide' } });
  assert.equal(page.data.activeTab, 'guide');
  page.switchBrowseTab({ detail: { key: 'route' } });
  assert.equal(page.data.activeTab, 'guide');
});

test('旧章节回退只使用真实正文和图片，不拿别的节点为图片编造标题', () => {
  const page = makePage();
  const blocks = page.buildGuideBlocks({ description: '旧章节', imgArr: 'a;b,c', nodes: [{ id: 3, name: '地点' }] });
  assert.deepEqual(blocks.map(block => block.type), ['text', 'image', 'image', 'image', 'node']);
  assert.equal(blocks[1].title, undefined);
  assert.deepEqual(blocks[4].images, []);
});

test('商家承接报名即使回包带评价和票种也不展示评价票种阵容', () => {
  const page = makePage();
  page.loadBrowseData();
  requests[0].success({ code: 200, data: { name: '招商项目', productType: 1,
    averageRating: 4.8, registrationMerchantCount: 9, clubName: '合作社群',
    totalTime: 7200, startDate: '2026-09-30', endDate: '2026-10-07',
    commentCount: 30, commentList: [{ id: 1 }], omsTicketList: [{ id: 1, name: '尚未使用的票' }],
  } });
  assert.equal(page.data.statBar.some(cell => cell.key === 'rating' || cell.key === 'merchant'), false);
  assert.equal(page.data.statBar.some(cell => cell.key === 'hour'), true);
  assert.ok(Array.isArray(page.data.info.contentIncludes));
  assert.doesNotMatch(WXML, /info\.(?:commentList|commentCount|averageRating|lineup|omsTicketList)/);
});

test('商家招商读取完整章节；旧经典入口仅不存在时回退一次，权限和网络失败不回退', () => {
  const page = makePage();
  page.onLoad({ id: '42', scope: 'MERCHANT' });
  const detail = requestByUrl('/api/topic/info-to-merchant');
  assert.ok(detail);
  detail.success({ code: 200, data: { name: '招商主题', productType: 1, chaptersList: [{
    id: 1, required: true, nodes: [{ id: 8, name: '第一站' }, { id: 9, name: '第二站' }],
    blocks: [{ type: 'node', nodeId: 8 }, { type: 'node', nodeId: 9 }],
  }] } });
  assert.deepEqual(page.data.info.chaptersList[0].guideBlocks.map(block => block.title), ['第一站', '第二站']);

  requests = [];
  const legacy = makePage();
  legacy.data.operationScope = 'MERCHANT';
  legacy.loadBrowseData();
  requests[0].success({ code: 500, msg: '主题活动不存在' });
  assert.equal(requests[1].url, '/api/topic/info-to-user');
  requests[1].success({ code: 500, msg: '主题活动不存在' });
  assert.equal(requests.length, 2, '公开回退也失败时不得循环请求');

  for (const mode of ['401', '403', 'network', 'managed']) {
    requests = [];
    const blocked = makePage();
    blocked.data.operationScope = 'MERCHANT';
    blocked._managedChapterProject = mode === 'managed';
    blocked.loadBrowseData();
    if (mode === 'network') requests[0].fail();
    else requests[0].success({ code: mode === 'managed' ? 500 : mode, msg: '主题活动不存在' });
    assert.equal(requests.length, 1, mode + ' 不得改读公开投影');
  }
  for (const scope of ['', 'CLUB']) {
    requests = [];
    const other = makePage();
    other.data.operationScope = scope;
    other.loadBrowseData();
    assert.equal(requests[0].url, '/api/topic/info-to-user');
  }
});

function withdrawControlVisible(application) {
  const tag = WXML.match(/<cy-btn\b[^>]*bindtap="withdrawChapterApplication"[^>]*>/);
  if (!tag) return false;
  const condition = tag[0].match(/wx:if="\{\{([^}]+)\}\}"/);
  if (!condition) return true;
  const field = condition[1].trim().match(/^application\.([A-Za-z][A-Za-z0-9]*)$/);
  if (!field) throw new Error('撤回入口使用了测试无法解释的条件: ' + condition[1]);
  return Boolean(application[field[1]]);
}

test('自由探索点申请承接先打开节点表单，不提前写入申请', () => {
  const page = makePage();
  Object.assign(page.data, {
    isJoin: 1,
    selectedMode: 2,
    selectedChapterId: 33,
    selectedNodeId: 99,
    selectedTemplateId: 88,
    topicId: 7,
    info: { lifecycle: 1, merchantSignUpStartDate: '2026-08-01', merchantSignUpEndDate: '2026-08-08' },
  });

  page.goSettlement();

  assert.equal(navigations.length, 0);
  assert.equal(requests.length, 0);
  assert.equal(page.data.topicShow, false);
  assert.equal(page.data.chapterNodeFormVisible, true);
});

test('申请链路严格按 apply → node/submit 串联，不提前写生效供给', () => {
  const page = makePage();
  page.data.info = { lifecycle: 1 };
  page.data.chapterNodeFormVisible = true;

  page.onChapterNodeFormSubmit({ detail: {
    chapterId: 33,
    message: '适合夜间活动',
    templateId: 88,
    name: '夜航咖啡',
    address: '星海街 8 号',
    longitude: '120.7',
    latitude: '31.3',
    xpValue: null,
  } });

  assert.equal(requests.length, 1, '首步只能提交承接申请');
  assert.equal(requests[0].url, '/api/merchant/chapter-application/apply');
  assert.equal(requests[0].header['Content-Type'], 'application/json');
  assert.deepEqual(JSON.parse(requests[0].data), { chapterId: 33, message: '适合夜间活动' });

  requests[0].success({ code: '200' });
  assert.equal(requests.length, 2, '申请闸通过后只能进入节点闸');
  assert.equal(requests[1].url, '/api/merchant/chapter-node/submit');
  assert.equal(requests[1].header['Content-Type'], 'application/json');
  assert.deepEqual(JSON.parse(requests[1].data), {
    chapterId: 33,
    templateId: 88,
    name: '夜航咖啡',
    address: '星海街 8 号',
    longitude: '120.7', latitude: '31.3',
    xpValue: null,
  });
  assert.equal(requestByUrl('/api/coop/offer/enroll'), undefined,
    '申请待审阶段不得越过 offer/enroll 的已批准授权闸');

  requests[1].success({ code: '200' });
  requests[1].complete();
  assert.equal(page.data.chapterNodeFormVisible, false, '申请和节点均成功后关闭表单');
});

test('已承接章节不依赖招募开放，配置原节点只走 node/submit 并读回本人进度', () => {
  const page = makePage();
  Object.assign(page.data, {
    topicId: 7,
    info: { lifecycle: 1, chaptersList: [{ id: 33, recruitStatus: { isOpen: false } }] },
    merchantChapters: [],
    myChapterApplications: [{ id: 71, chapterId: 33, chapterName: '咖啡章', status: 1, termsMode: 'PERK' }],
    myChapterNodes: [{ id: 80, chapterId: 33, name: '旧节点', templateId: 88,
      longitude: '120.7', latitude: '31.3' }],
  });
  page.openChapterNodeForm({ currentTarget: { dataset: { chapterId: 33 } } });
  assert.equal(page.data.chapterNodeFormMode, 'node');
  assert.equal(page.data.presetChapterNode.id, 80);
  assert.equal(page.data.chapterNodeFormVisible, true);
  assert.equal(requests.length, 0);
  page.onChapterNodeFormSubmit({ detail: { chapterId: 33, templateId: 88, name: '原节点新名称',
    address: '真实地址', longitude: 120.7, latitude: 31.3, xpValue: 12, id: 999, merchantId: 999 } });
  assert.deepEqual(requests.map(row => row.url), ['/api/merchant/chapter-node/submit']);
  assert.deepEqual(JSON.parse(requests[0].data), {
    chapterId: 33, templateId: 88, name: '原节点新名称', address: '真实地址',
    longitude: '120.7', latitude: '31.3', xpValue: 12,
  }, '只传现有 DTO 白名单，不接受伪造节点或商家归属');
  requests[0].success({ code: 200 });
  requests[0].complete();
  assert.equal(page.data.chapterNodeFormVisible, false);
  assert.ok(requestByUrl('/api/merchant/chapter-node/mine'));
  assert.ok(requestByUrl('/api/merchant/chapter-application/mine'));
  assert.equal(requestByUrl('/api/merchant/chapter-application/apply'), undefined);
  assert.match(WXML, /bindtap="openChapterNodeForm">配置节点/);
});

test('本人待审承接可首次配置节点，冻结拒绝后保留表单供读回和修正', () => {
  const page = makePage();
  page.data.info = { lifecycle: 1 };
  page.data.myChapterApplications = [{ id: 71, chapterId: 33, status: 0 }];
  page.openChapterNodeForm({ currentTarget: { dataset: { chapterId: 33 } } });
  assert.equal(page.data.presetChapterNode, null);
  page.onChapterNodeFormSubmit({ detail: { chapterId: 33, templateId: 88, name: '真实节点',
    address: '真实地址', longitude: '120.7', latitude: '31.3' } });
  requests[0].success({ code: 500, msg: '主题已锁价，不可修改节点' });
  requests[0].complete();
  assert.equal(requests.length, 1);
  assert.equal(page.data.chapterNodeFormVisible, true);
  assert.equal(page.data.chapterNodeSubmitting, false);
  assert.equal(tips.at(-1), '主题已锁价，不可修改节点');
});

test('旧章节选择器或结算入口重选本人已承接章节，也不重复调用 apply', () => {
  for (const entry of ['picker', 'legacy']) {
    const page = makePage();
    Object.assign(page.data, {
      info: { lifecycle: 1 },
      myChapterApplications: [{ id: 71, chapterId: 33, status: 1 }],
      selectedMode: 2, selectedChapterId: 33,
    });
    if (entry === 'picker') page.onChapterTargetConfirm({ detail: { chapterId: 33, templateId: 88 } });
    else page.goSettlement();
    assert.equal(page.data.chapterNodeFormMode, 'node', entry);
    page.onChapterNodeFormSubmit({ detail: { chapterId: 33, templateId: 88, name: '原节点',
      address: '真实地址', longitude: '120.7', latitude: '31.3' } });
    assert.equal(requestByUrl('/api/merchant/chapter-application/apply'), undefined, entry);
    assert.ok(requestByUrl('/api/merchant/chapter-node/submit'), entry);
    requests = [];
  }
});

test('已退出历史、拒绝申请和不属于当前承接的章节不能打开或续交节点', () => {
  for (const row of [{ chapterId: 33, status: 1, offerState: 'WITHDRAWN' },
    { chapterId: 33, status: 2 }, { chapterId: 34, status: 1 }]) {
    const page = makePage();
    page.data.info = { lifecycle: 1 };
    page.data.myChapterApplications = [row];
    page.openChapterNodeForm({ currentTarget: { dataset: { chapterId: 33 } } });
    assert.equal(page.data.chapterNodeFormVisible, false);
    page.data.chapterNodeFormMode = 'node';
    page.data.selectedChapterId = 33;
    page.onChapterNodeFormSubmit({ detail: { chapterId: 33, templateId: 88, name: '节点' } });
    assert.equal(requests.length, 0);
  }
});

test('未来计划开始日期不能解冻节点，草稿、2..6、未知和已退出的所有入口均不写入', () => {
  const draft = { chapterId: 33, templateId: 88, name: '原节点',
    address: '真实地址', longitude: '120.7', latitude: '31.3' };
  const cases = [
    { lifecycle: 0, reason: /草稿/ },
    ...[2, 3, 4, 5, 6, '2', '3'].map(lifecycle => ({ lifecycle, reason: /主题已锁定/ })),
    ...[undefined, null, '', ' ', 99, 'unknown', true].map(lifecycle => ({ lifecycle, reason: /刷新详情后重试/ })),
    { lifecycle: 1, offerState: 'WITHDRAWN', reason: /历史记录/ },
  ];
  for (const state of cases) {
    for (const entry of ['button', 'picker', 'legacy', 'application', 'node', 'send']) {
      requests = [];
      const page = makePage();
      Object.assign(page.data, {
        info: { lifecycle: state.lifecycle, startDate: '2099-01-01', topicStartDate: '2099-01-01' },
        myChapterApplications: [{ id: 71, chapterId: 33, status: 1, termsMode: 'TRAFFIC', offerState: state.offerState }],
        selectedMode: 2, selectedChapterId: 33,
      });
      page.refreshMyChapterApplications();
      assert.equal(page.data.myChapterApplications[0].canConfigureNode, false);
      assert.match(page.data.myChapterApplications[0].nodeReadOnlyReason, state.reason);
      page.data.myChapterApplications[0].canConfigureNode = true;
      const label = String(state.lifecycle) + '/' + entry + '/' + (state.offerState || '');
      if (entry === 'button') page.openChapterNodeForm({ currentTarget: { dataset: { chapterId: 33 } } });
      else if (entry === 'picker') page.onChapterTargetConfirm({ detail: { chapterId: 33, templateId: 88 } });
      else if (entry === 'legacy') page.goSettlement();
      else if (entry === 'send') {
        page._chapterApplying = true;
        page.data.chapterNodeSubmitting = true;
        page.submitChapterNodeDraft(draft);
        assert.equal(page._chapterApplying, false, label);
        assert.equal(page.data.chapterNodeSubmitting, false, label);
      } else {
        page.data.chapterNodeFormMode = entry;
        page.onChapterNodeFormSubmit({ detail: draft });
      }
      assert.equal(page.data.chapterNodeFormVisible, false, label);
      assert.equal(requests.length, 0, label + ' 不得调用 apply 或 node/submit');
    }
  }
  assert.match(WXML, /<cy-btn\b[^>]*wx:if="\{\{application\.canConfigureNode\}\}"[^>]*bindtap="openChapterNodeForm"/);
  assert.match(WXML, /wx:if="\{\{application\.nodeReadOnlyReason\}\}">\{\{application\.nodeReadOnlyReason\}\}/);
});

test('招商1和待审7允许本人待审或已承接章节配置，三个入口都直接续交节点', () => {
  for (const lifecycle of [1, 7, '1', '7']) {
    for (const status of [0, 1]) {
      for (const entry of ['button', 'picker', 'legacy']) {
        requests = [];
        const page = makePage();
        Object.assign(page.data, {
          info: { lifecycle },
          myChapterApplications: [{ id: 71, chapterId: 33, status }],
          selectedMode: 2, selectedChapterId: 33,
        });
        page.refreshMyChapterApplications();
        assert.equal(page.data.myChapterApplications[0].canConfigureNode, true);
        assert.equal(page.data.myChapterApplications[0].nodeReadOnlyReason, '');
        if (entry === 'button') page.openChapterNodeForm({ currentTarget: { dataset: { chapterId: 33 } } });
        else if (entry === 'picker') page.onChapterTargetConfirm({ detail: { chapterId: 33, templateId: 88 } });
        else page.goSettlement();
        assert.equal(page.data.chapterNodeFormVisible, true);
        assert.equal(page.data.chapterNodeFormMode, 'node');
        page.onChapterNodeFormSubmit({ detail: { chapterId: 33, templateId: 88, name: '节点' } });
        assert.deepEqual(requests.map(row => row.url), ['/api/merchant/chapter-node/submit']);
      }
    }
  }
});

test('招商1和待审7的新申请仍先申请再提交节点', () => {
  for (const lifecycle of [1, 7]) {
    requests = [];
    const page = makePage();
    page.data.info = { lifecycle };
    page.onChapterTargetConfirm({ detail: { chapterId: 33, templateId: 88 } });
    assert.equal(page.data.chapterNodeFormMode, 'application');
    page.onChapterNodeFormSubmit({ detail: { chapterId: 33, templateId: 88, name: '新节点' } });
    assert.deepEqual(requests.map(row => row.url), ['/api/merchant/chapter-application/apply']);
    requests[0].success({ code: 200 });
    assert.deepEqual(requests.map(row => row.url), [
      '/api/merchant/chapter-application/apply', '/api/merchant/chapter-node/submit',
    ]);
  }
});

test('申请回执期间主题进入锁价、销售或状态未知，实际发送节点前重新守卫并解除提交中', () => {
  for (const lifecycle of [2, 3, null]) {
    requests = [];
    const page = makePage();
    page.data.info = { lifecycle: 1, startDate: '2099-01-01' };
    page.data.chapterNodeFormVisible = true;
    page.onChapterNodeFormSubmit({ detail: { chapterId: 33, templateId: 88, name: '新节点' } });
    assert.equal(page.data.chapterNodeSubmitting, true);
    page.data.info.lifecycle = lifecycle;
    requests[0].success({ code: 200 });
    assert.deepEqual(requests.map(row => row.url), ['/api/merchant/chapter-application/apply']);
    assert.equal(page.data.chapterNodeFormVisible, true, '保留输入供详情刷新后重试');
    assert.equal(page.data.chapterNodeSubmitting, false);
    assert.equal(page._chapterApplying, false);
  }
});

test('apply 回复已申请时只续交节点，其他失败不越过申请闸', () => {
  const draft = {
    chapterId: 33, message: '', templateId: 88, name: '咖啡点', address: '', xpValue: 12,
  };
  const retryPage = makePage();
  retryPage.data.info = { lifecycle: 1 };
  retryPage.onChapterNodeFormSubmit({ detail: draft });
  requests[0].success({ code: '500', errorCode: 'CHAPTER_APPLICATION_EXISTS', msg: '文案可变' });
  assert.equal(requests[1].url, '/api/merchant/chapter-node/submit');

  requests = [];
  const failedPage = makePage();
  failedPage.data.info = { lifecycle: 1 };
  failedPage.onChapterNodeFormSubmit({ detail: draft });
  requests[0].success({ code: '500', msg: '章节已关闭招募' });
  assert.equal(requests.length, 1, '真实 apply 失败不得继续 submit');
  assert.equal(tips.at(-1), '章节已关闭招募');
});

test('apply 已存在冲突优先认稳定错误码，不依赖中文文案', () => {
  const page = makePage();
  page.data.info = { lifecycle: 1 };
  page.onChapterNodeFormSubmit({ detail: {
    chapterId: 33,
    message: '',
    templateId: 88,
    name: '咖啡点',
    address: '',
    xpValue: 12,
  } });

  requests[0].success({
    code: '500',
    errorCode: 'CHAPTER_APPLICATION_EXISTS',
    msg: '状态已变更，请刷新',
  });

  assert.equal(requests.length, 2,
    '后端文案改字时，稳定码仍必须让申请链安全续到节点闸');
  assert.equal(requests[1].url, '/api/merchant/chapter-node/submit');
});

test('只有待审承接申请向商家显示撤回入口', () => {
  const page = makePage();
  page.data.myChapterApplications = [
    { id: 71, chapterId: 31, status: 0 },
    { id: 72, chapterId: 32, status: 1 },
    { id: 73, chapterId: 33, status: 2 },
  ];

  page.refreshMyChapterApplications();

  assert.equal(withdrawControlVisible(page.data.myChapterApplications[0]), true);
  assert.equal(withdrawControlVisible(page.data.myChapterApplications[1]), false);
  assert.equal(withdrawControlVisible(page.data.myChapterApplications[2]), false);
});

test('撤回待审申请须二次确认，取消确认不发请求', () => {
  // 2026-08-27:二次确认从 wx.showModal 换成 cy-danger-confirm 三段式,断言的意思不变 ——
  // 没确认就不许发请求;确认文案里必须写明「此操作不可撤销」。
  const page = makePage();
  const dc = attachDangerConfirm(page, { confirmHandler: 'onConfirmWithdrawApplication' });
  page.data.myChapterApplications = [{ id: 71, chapterId: 31, status: 0 }];

  page.withdrawChapterApplication({ currentTarget: { dataset: { applicationId: 71 } } });

  assert.equal(requests.length, 0);
  assert.equal(dc.opens.length, 1, '撤回必须先弹确认');
  assert.ok(
    dc.opens[0].action.consequences.some(item => item.text.includes('此操作不可撤销')),
    '不可逆动作的确认文案必须写明不可撤销',
  );
  dc.cancel();
  assert.equal(requests.length, 0, '商家取消二次确认后不得核销或撤回任何记录');

  dc.confirm();
  const withdrawRequest = requestByUrl('/api/merchant/chapter-application/withdraw');
  assert.ok(withdrawRequest);
  assert.deepEqual(JSON.parse(withdrawRequest.data), { applicationId: 71 });

  withdrawRequest.success({ code: '200' });
  assert.ok(requestByUrl('/api/merchant/chapter-application/mine'),
    '撤回回执后必须从服务端刷新可重新申请状态');
  assert.match(dc.doneCalls.length ? '已撤回，可重新提交' : '', /可重新提交/,
    '成功后必须走结果确认卡(第三段),不是一闪而过的 toast');
});

test('撤回不存在或状态冲突都认稳定错误码并回读，不依赖中文文案', () => {
  for (const errorCode of ['CHAPTER_APPLICATION_NOT_FOUND', 'CHAPTER_APPLICATION_STATE_CHANGED']) {
    requests = [];
    tips = [];
    modals = [];
    const page = makePage();
    page.data.myChapterApplications = [{ id: 71, chapterId: 31, status: 0 }];

    const dc = attachDangerConfirm(page, { confirmHandler: 'onConfirmWithdrawApplication' });
    page.withdrawChapterApplication({ currentTarget: { dataset: { applicationId: 71 } } });
    dc.confirm();
    requestByUrl('/api/merchant/chapter-application/withdraw').success({
      code: '500',
      errorCode,
      msg: '服务端展示文案已经改名',
    });

    assert.ok(requestByUrl('/api/merchant/chapter-application/mine'),
      `${errorCode} 必须触发服务端状态刷新`);
    // 失败留在确认弹窗原地给重试(第二段的失败态),不再靠 app.tips 飘一下
    assert.equal(dc.failedCalls.at(-1), '申请状态已变化，请刷新后重试');
  }
});

test('只有已通过且尚无生效供给的申请能打开实际供给表单', () => {
  const page = makePage();
  page.data.myChapterApplications = [
    { chapterId: 31, status: 0, offerActive: false, termsMode: 'TRAFFIC' },
    { chapterId: 32, status: 2, offerActive: false, termsMode: 'REVSHARE' },
    { chapterId: 33, status: 1, offerActive: false, termsMode: 'PERK', chapterName: '夜航咖啡' },
  ];

  page.openChapterOfferForm({ currentTarget: { dataset: { chapterId: 31 } } });
  assert.equal(page.data.chapterNodeFormVisible, false, '待审核申请不得越过审核通过闸');
  page.openChapterOfferForm({ currentTarget: { dataset: { chapterId: 32 } } });
  assert.equal(page.data.chapterNodeFormVisible, false, '已驳回申请不得越过审核通过闸');
  page.openChapterOfferForm({ currentTarget: { dataset: { chapterId: 33 } } });
  assert.equal(page.data.chapterNodeFormVisible, true, '已通过申请应进入实际供给表单');
  assert.equal(page.data.chapterNodeFormMode, 'offer');
});

test('已有承接申请时可进入进度弹层，不受新申请窗口与可申请章节空态阻断', () => {
  const page = makePage();
  Object.assign(page.data, {
    info: { productType: 2, memberId: 2, merchantStatus: 0 },
    allChaptersList: [],
    myChapterApplications: [
      { chapterId: 33, chapterName: '夜航咖啡', status: 1, offerActive: false, termsMode: 'PERK' },
    ],
  });

  // CU-M-07(2026-09-23):底部「承接进度」只滚到正文的进度块,不再弹「申请承接章节」。
  const scrolls = [];
  const originalScroll = wx.pageScrollTo;
  wx.pageScrollTo = (opts) => scrolls.push(opts);
  try {
    page.bmClick2({ currentTarget: { dataset: {} } });
  } finally {
    wx.pageScrollTo = originalScroll;
  }
  assert.deepEqual(scrolls.map(o => o.selector), ['.my-apply'], '承接进度按钮必须落到进度块');
  assert.notEqual(page.data.topicShow, true, '承接进度按钮不得打开申请弹层');

  // 进度块里「申请承接其他章节」(data-apply)才开弹层,且不受新申请窗口闸阻断。
  page.bmClick2({ currentTarget: { dataset: { apply: '1' } } });
  assert.equal(page.data.topicShow, true,
    '已批准申请的供给入口不得被报名期限、招募开关或当前章节满额闸挡在弹层外');
  assert.equal(page.data.chapterApplicationOpen, false,
    '绕过的是既有申请进度入口，不得顺便放开新的章节申请');
});

test('F-45 自由探索报名只按已加载的章节招募状态开窗，并区分全关与加载失败', () => {
  const openPage = makePage();
  Object.assign(openPage.data, {
    info: {
      productType: 2,
      memberId: 2,
      merchantStatus: 0,
      merchantSignUpStartDate: null,
      merchantSignUpEndDate: null,
    },
    chapterRecruitmentState: 'ready',
    allChaptersList: openPage.buildMerchantChapters([{
      id: 31,
      name: '夜航咖啡',
      recruitStatus: { state: 'OPEN', isOpen: true, termsMode: 'PERK' },
    }]),
    myChapterApplications: [],
  });

  openPage.bmClick2();

  assert.equal(openPage.data.topicShow, true,
    '已有开放章节时不得被 legacy 主题报名日期或主题级 merchantStatus 挡住');
  assert.equal(openPage.data.chapterApplicationOpen, true);
  assert.equal(openPage.data.selectedChapterId, 31);
  assert.equal(toasts.some(item => item.title === '报名时间信息不完整'), false);

  const closedPage = makePage();
  Object.assign(closedPage.data, {
    info: { productType: 2, memberId: 2 },
    chapterRecruitmentState: 'ready',
    allChaptersList: closedPage.buildMerchantChapters([{
      id: 32,
      name: '收官章',
      recruitStatus: { state: 'CLOSED', isOpen: false },
    }]),
    myChapterApplications: [],
  });
  closedPage.bmClick2();
  assert.equal(closedPage.data.topicShow, true,
    '全关时应打开既有选择层的业务空态，不能伪装成日期配置问题');
  assert.equal(closedPage.data.chapterApplicationOpen, false);
  assert.equal(toasts.some(item => item.title === '报名时间信息不完整'), false);

  const failedPage = makePage();
  Object.assign(failedPage.data, {
    info: { productType: 2, memberId: 2 },
    chapterRecruitmentState: 'error',
    chapterRecruitmentError: '网络异常，可承接章节加载失败',
    allChaptersList: [],
    myChapterApplications: [],
  });
  failedPage.bmClick2();
  assert.equal(failedPage.data.topicShow, true,
    '加载失败时应打开既有错误态与重试入口，不能伪装成日期配置问题');
  assert.equal(failedPage.data.chapterApplicationOpen, false);
  assert.equal(toasts.some(item => item.title === '报名时间信息不完整'), false);
  assert.match(PICKER_WXML,
    /wx:if="\{\{!applicationOpen && chaptersState === 'ready' && rows\.length\}\}"/,
    '加载失败或全关已有各自状态文案时，不得再误报主题招商窗口已关闭');
});

test('服务端 offerActive 回读决定冻结，重进页面不能靠内存状态解冻', () => {
  const page = makePage();
  page.data.topicId = 42;
  page.data.info = { chaptersList: [{ id: 33, termsMode: 'PERK' }] };
  page.loadMyChapterApplications();

  const mineRequest = requestByUrl('/api/merchant/chapter-application/mine');
  assert.ok(mineRequest, '冻结事实必须主动从我的章节申请接口刷新');
  mineRequest.success({
    code: '200',
    data: [{ topicId: 42, chapterId: 33, chapterName: '夜航咖啡', status: 1, offerActive: true }],
  });
  page.openChapterOfferForm({ currentTarget: { dataset: { chapterId: 33 } } });

  assert.equal(page.data.chapterNodeFormVisible, false,
    '服务端已有生效 offer 时必须停在只读闸，不能重开空表单覆盖快照');
  assert.equal(page.data.myChapterApplications[0].offerReadOnly, true,
    '只读态必须由服务端 offerActive 映射，而不是页面提交回调自封');
});

test('审核通过后的供给提交只写 offer/enroll，并在成功后重新回读冻结事实', () => {
  const page = makePage();
  Object.assign(page.data, {
    info: { lifecycle: 3 },
    chapterNodeFormVisible: true,
    chapterNodeFormMode: 'offer',
    myChapterApplications: [{ chapterId: 33, status: 1, offerActive: false, termsMode: 'PERK' }],
  });

  page.onChapterNodeFormSubmit({ detail: {
    chapterId: 33, termsMode: 'PERK', perkTemplateId: 7, quotaTotal: 12,
  } });

  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, '/api/coop/offer/enroll');
  assert.deepEqual(JSON.parse(requests[0].data), {
    chapterId: 33, termsMode: 'PERK', perkTemplateId: 7, quotaTotal: 12,
  });
  requests[0].success({ code: '200' });
  assert.equal(page.data.chapterNodeFormVisible, false);
  assert.ok(requestByUrl('/api/merchant/chapter-application/mine'),
    'offer 写入回执后必须再向服务端读取 offerActive，不能本地直接置冻结');
});

test('经典定向保持节点和模板目标，不接受章节代替节点', () => {
  const page = makePage();
  Object.assign(page.data, {
    isJoin: 1,
    selectedMode: 1,
    selectedNodeId: 99,
    selectedTemplateId: 88,
    topicId: 7,
    info: { merchantSignUpStartDate: '2026-08-01', merchantSignUpEndDate: '2026-08-08' },
  });

  page.goSettlement();

  // 2026-07-31:这条入口恒为经典定向(mode=1),改走弹窗规范类型 B 旗舰案例——
  // 一个 cy-sheet 全屏弹窗页收完"填写→提交→成功",不再连跳三个整页 merchantapply1。
  assert.match(navigations[0].url, /^\/pages\/topic\/merchantapply\/index\?/,
    '经典定向改走 cy-sheet 旗舰案例页，不再是三页版 merchantapply1');
  assert.match(navigations[0].url, /nodeId=99/);
  assert.match(navigations[0].url, /templateId=88/);
  assert.doesNotMatch(navigations[0].url, /chapterId=/);
});

test('自由探索报名详情用承接章节而不是任务或模板来解释分配结果', () => {
  const page = makePage();

  page.processData({
    mode: 2,
    chapterName: '夜航咖啡',
    totalOrderNum: 0,
    verifiedNum: 0,
  });

  assert.equal(page.data.assignedLabel, '承接的章节');
  assert.equal(page.data.info.chapterName, '夜航咖啡');
});

test('只展示服务端判定可申请的章节，并采用章节承接状态', () => {
  const page = makePage();

  const chapters = page.buildMerchantChapters([
    {
      id: 1,
      name: '咖啡夜航',
      category: '咖啡',
      required: 1,
      recruitStatus: {
        isOpen: true,
        termsMode: 'PERK',
        remainingMerchantCount: 2,
        maxMerchant: 3,
        allowedValidationMethods: '1,5',
        maxNodeXp: 80,
      },
    },
    {
      id: 2,
      name: '已满章节',
      recruitStatus: { isOpen: false, termsMode: 'TRAFFIC', remainingMerchantCount: 0, maxMerchant: 1 },
    },
  ]);

  assert.deepEqual(chapters.map(item => item.id), [1]);
  assert.equal(chapters[0].termsLabel, '权益承接');
  assert.equal(chapters[0].merchantLimitLabel, '剩余 2 / 3 家可承接');
  assert.equal(chapters[0].boundaryLabel, '玩法限 文字作答/GPS 到达 · 探索值上限 80');
});

test('获准未填供给的项目读取本人章节条款，不依赖剩余招商名额', () => {
  const page = makePage();
  Object.assign(page.data, { topicId: 42, operationScope: 'MERCHANT' });
  page.loadProjectHome();
  requestByUrl('/api/project/home').success({ code: 200, data: {
    role: 'join', topic: { id: 42, name: '已承接的主题', lifecycle: 7 },
    join: { chapterApplications: [{ id: 71, topicId: 42, chapterId: 31, status: 1, offerActive: false }] },
  } });
  assert.equal(page.data.myChapterApplications[0].canSubmitOffer, false);
  const detail = requestByUrl('/api/topic/info-to-merchant');
  assert.ok(detail, '摘要已有生命周期也必须补读取获准章节档位');
  assert.deepEqual(detail.data, { id: 42, scope: 'MERCHANT' });
  detail.success({ code: 200, data: { id: 42, lifecycle: 7,
    chaptersList: [{ id: 31, termsMode: 'TRAFFIC' }] } });
  assert.equal(page.data.myChapterApplications[0].termsMode, 'TRAFFIC');
  assert.equal(page.data.myChapterApplications[0].canSubmitOffer, true);
  assert.deepEqual(page.data.allChaptersList, [], '已承接条款不扩充开放招商候选');
  assert.equal(page.data.loadFailed, false);
});

test('主办方打开项目页才读取自己的章节申请队列', () => {
  const host = makePage();
  host.data.topicId = 42;
  host.loadProjectHome();
  requestByUrl('/api/project/home').success({
    code: '200', data: { role: 'host', topic: { id: 42, productType: 2 }, host: {} },
  });

  const ownerRequest = requestByUrl('/api/merchant/chapter-application/owner-list');
  assert.ok(ownerRequest, '主办方项目页必须读取自己主题下的申请队列');
  assert.equal(ownerRequest.method, 'POST');
  assert.deepEqual(ownerRequest.header, { 'Content-Type': 'application/json' });
  assert.deepEqual(JSON.parse(ownerRequest.data), { topicId: 42, scope: '' });

  requests = [];
  const join = makePage();
  join.data.topicId = 42;
  join.loadProjectHome();
  requestByUrl('/api/project/home').success({
    code: '200', data: { role: 'join', join: { registration: { id: 7 }, registrations: [] } },
  });
  assert.equal(requestByUrl('/api/merchant/chapter-application/owner-list'), undefined,
    '非主办方不得读取 owner-list');
});

test('章节承接方没有旧报名记录时进入真实join导览，不回退招募浏览', () => {
  const page = makePage();
  page.data.topicId = 42;
  let browseLoads = 0;
  page.loadBrowseData = () => { browseLoads += 1; };
  page.loadProjectHome();
  const data = {
    role: 'join', topic: { id: 42, name: '已承接的主题', productType: 2 },
    join: { chapterApplications: [{ id: 71, topicId: 42, chapterId: 31, status: 1, offerActive: true }] },
  };
  requestByUrl('/api/project/home').success({ code: '200', data });
  assert.equal(page.data.loadFailed, false);
  assert.equal(page.data.role, 'join');
  assert.equal(page.data.fromMerchantJoin, false, '不再回退报名/招募浏览视图');
  assert.equal(page.data.chapterProject, true);
  assert.equal(page.data.regId, 0, '不得伪造旧registration id');
  assert.equal(page.data.info.topicName, '已承接的主题');
  assert.equal(browseLoads, 0);
  assert.ok(requestByUrl('/api/merchant/chapter-node/mine'));
  assert.equal(requestByUrl('/api/merchant/chapter-application/owner-list'), undefined,
    '承办方不得读取主办申请列表');
  // 走查 v2 R15(2026-10-30):承办只留扫码 / 二维码 / 客户 + 编辑供给
  assert.deepEqual(page.data.quickActions.map(item => item.key), ['scan', 'chapterCheckin', 'customer', 'chapterEdit'],
    '不得借用商家协作邀请、主办编辑或其他报名的运营入口');
  for (const badData of [
    { ...data, topic: undefined },
    { ...data, topic: { id: 43, name: '别的主题' } },
    { ...data, role: 'unknown' },
    { ...data, join: { chapterApplications: [{ id: 71, topicId: 43, chapterId: 31 }] } },
    { ...data, join: { chapterApplications: [null] } },
  ]) {
    requests = [];
    const invalid = makePage(); invalid.data.topicId = 42;
    invalid.loadProjectHome();
    requestByUrl('/api/project/home').success({ code: '200', data: badData });
    assert.equal(invalid.data.loadFailed, true, '坏/异主题投影必须拒绝，不伪装已承接');
    assert.equal(requestByUrl('/api/merchant/chapter-node/mine'), undefined);
  }
});

test('章节快捷编辑和出示码复用本人节点，多个章节先选择且过期选择不取码', () => {
  const page = makePage();
  Object.assign(page.data, { chapterProject: true, topicId: 42, info: { lifecycle: 7 },
    myChapterApplications: [{ chapterId: 31, chapterName: '第一章', status: 1, canConfigureNode: true }],
    myChapterNodes: [{ id: 99, chapterId: 31, name: '本人节点' }] });
  page.onQuickAction({ currentTarget: { dataset: { key: 'chapterEdit' } } });
  assert.equal(page.data.chapterNodeFormVisible, true);
  assert.equal(page.data.chapterNodeFormMode, 'node');
  assert.equal(page.data.selectedChapterId, 31);
  assert.equal(page.data.presetChapterNode.id, 99);
  page.onQuickAction({ currentTarget: { dataset: { key: 'chapterCheckin' } } });
  assert.equal(page.data.checkinVisible, true);
  assert.deepEqual(requestByUrl('/api/merchant/chapter-node/poster-code').data, { nodeId: 99 });
  requests.at(-1).success({ code: 500, msg: '该节点还没过审，过审后再取码' });
  assert.equal(page.data.checkinQrState, 'error');
  assert.equal(page.data.checkinQrUrl, '');

  page.data.myChapterApplications.push({ chapterId: 32, chapterName: '第二章', status: 1, canConfigureNode: true });
  page.data.myChapterNodes.push({ id: 100, chapterId: 32, name: '第二节点' });
  page.onQuickAction({ currentTarget: { dataset: { key: 'chapterEdit' } } });
  assert.deepEqual(page.data.stationSheetItems, ['第一章', '第二章']);
  page.onStationSheetSelect({ detail: { index: 1 } });
  assert.equal(page.data.selectedChapterId, 32);
  assert.equal(page.data.presetChapterNode.id, 100);
  page.onQuickAction({ currentTarget: { dataset: { key: 'chapterCheckin' } } });
  assert.deepEqual(page.data.stationSheetItems, ['本人节点', '第二节点']);
  page.data.myChapterNodes.pop();
  requests = [];
  page.onStationSheetSelect({ detail: { index: 1 } });
  assert.equal(requests.length, 0, '选中节点已不在本人列表时不得请求码');
  page.data.myChapterApplications.forEach(row => { row.canConfigureNode = false; });
  page.data.chapterNodeFormVisible = false;
  page.onQuickAction({ currentTarget: { dataset: { key: 'chapterEdit' } } });
  assert.equal(page.data.chapterNodeFormVisible, false);
  assert.equal(tips.at(-1), '当前没有可编辑的章节');
});

test('多章节编辑与出示码的选择面板挂在共用商家根节点，不受招募浏览条件限制', () => {
  const views = [];
  let panels = 0;
  const markup = WXML.replace(/<!--[\s\S]*?-->/g, '');
  for (const match of markup.matchAll(/<\/?view\b(?:[^"'<>]|"[^"]*"|'[^']*')*>|<cy-option-sheet\b/g)) {
    const tag = match[0];
    if (tag.startsWith('</view')) views.pop();
    else if (tag.startsWith('<view')) views.push(tag);
    else {
      panels += 1;
      assert.equal(views.length, 1, '选择面板必须在共用根节点内，承接管理也能打开');
      assert.match(views[0], /merchant-info-page theme-merchant/);
    }
  }
  assert.equal(panels, 1, '浏览和管理复用一个选择面板');
});

test('下架章节承接项目使用/home商家授权投影，不回退玩家公开详情', () => {
  const page = makePage();
  page.data.topicId = 42;
  page.data.operationScope = 'MERCHANT';
  page.loadProjectHome();
  const homeRequest = requestByUrl('/api/project/home');
  assert.deepEqual(JSON.parse(homeRequest.data), { topicId: 42, scope: 'MERCHANT' });
  homeRequest.success({
    code: '200', data: {
      role: 'join', topic: { id: 42, name: '已下架但仍在履约', productType: 2, status: 0, lifecycle: 2 },
      join: { chapterApplications: [{ id: 71, topicId: 42, chapterId: 31, status: 1, offerActive: true }] },
    },
  });
  assert.equal(page.data.fromMerchantJoin, false);
  assert.equal(page.data.info.topicName, '已下架但仍在履约');
  assert.equal(page.data.info.lifecycle, 2, '/home带回商业冻结状态，不从公开详情补猜');
  assert.equal(requestByUrl('/api/topic/info-to-user'), undefined,
    '公开下架不能决定承接管理项目是否存在');
  assert.equal(requestByUrl('/api/topic/info-to-merchant'), undefined,
    '/home已提供合法主题投影，不用招商详情拼管理页');
  const mine = requestByUrl('/api/merchant/chapter-node/mine');
  assert.deepEqual(mine.data, { topicId: 42 });
  mine.fail({ msg: '节点接口暂不可用' });
  assert.equal(page.data.loadFailed, false, '节点读取失败不能遮住有效项目导览');
  assert.equal(page.data.myChapterNodesLoaded, false, '读取失败不冒充空节点');
  assert.equal(page.data.myChapterNodesError, '节点接口暂不可用');
  page.retryMyChapterNodes();
  requests.at(-1).success({ code: '200', data: [{ id: 9, name: '本人的节点', nodeAuditStatus: 1 }] });
  assert.equal(page.data.myChapterNodes[0].auditLabel, '已通过');
  assert.equal(page.data.myChapterNodesLoaded, true);
  assert.equal(page.data.myChapterNodesError, '');
  assert.equal(page.formatShortDate('2026-09-27T16:30:00Z'), '周一 9月28日');
  assert.equal(page.formatDayMonth('2026-09-27T16:30:00Z'), '9月28日');
  assert.equal(page.formatStamp('2026-09-27T16:30:00Z', true), '9.28 周一 00:30');
  assert.equal(page.formatStamp('2026-09-28', true), '9.28 周一', 'date-only不得编造00:00');
});

test('承接方多站列表包含空元素时 fail-closed，不进入详情链路', () => {
  const page = makePage();
  page.data.topicId = 42;
  let detailLoads = 0;
  page.loadDetail = () => { detailLoads += 1; };
  page.loadProjectHome();

  assert.doesNotThrow(() => requestByUrl('/api/project/home').success({
    code: '200',
    data: {
      role: 'join',
      join: { registration: { id: 7 }, registrations: [null, null] },
    },
  }));
  assert.equal(page.data.loadFailed, true);
  assert.equal(detailLoads, 0);
});

test('项目详情的申请、节点、场次、合作方和玩家列表都拒绝空元素', () => {
  const page = makePage();
  page.data.topicId = 42;

  page.loadMyChapterApplications();
  assert.doesNotThrow(() => requests.at(-1).success({ code: '200', data: [null] }));
  assert.equal(page.data.myChapterApplicationsLoaded, false);

  page.loadMyChapterNodes();
  assert.doesNotThrow(() => requests.at(-1).success({ code: '200', data: [null] }));
  assert.equal(page.data.myChapterNodesLoaded, false);

  page.loadUpcomingRuns();
  assert.doesNotThrow(() => requests.at(-1).success({ code: '200', data: [null] }));
  // 2026-09-05「接下来」改「进度」后场次不再上屏,runs 收回成实例字段(wxml 零引用,
  // 不该走 setData)。保护目标没变:脏数据里的 null 元素不能让它崩或塞进列表。
  assert.deepEqual(page._runs, []);

  page.loadCoop();
  assert.doesNotThrow(() => requests.at(-1).success({
    code: '200', data: { role: 'host', host: { clubs: [null] } },
  }));
  assert.equal(page.data.coopState, 'error');

  page.openPlayerSheet();
  assert.doesNotThrow(() => requestByUrl('/api/project/players').success({
    code: '200', data: { rows: [null] },
  }));
  // CU-2026-09-24 C-67:脏数据的拒绝从「空态文案」挪到了错态字段 —— 名单没拿到
  // 与「一单没卖」必须分开说,不能再共用 emptyText(那一句会被读成真空态)。
  assert.equal(page.data['playerSheet.errorText'], '名单加载失败');
  assert.equal(page.data['playerSheet.emptyText'], '');
});

test('权益章节公开最低权益价值，引流档不展示该门槛', () => {
  const page = makePage();
  const chapters = page.buildMerchantChapters([
    {
      id: 11, name: '夜航咖啡线',
      recruitStatus: { isOpen: true, termsMode: 'PERK', perkMinValue: '50.00' },
    },
    {
      id: 12, name: '城市漫步线',
      recruitStatus: { isOpen: true, termsMode: 'TRAFFIC', perkMinValue: '99.00' },
    },
  ]);

  assert.equal(chapters[0].perkMinValueLabel, '权益零售价不少于 ¥50');
  assert.equal(chapters[1].perkMinValueLabel, '');
});

test('商家打开自由探索承接页时就读取自己的节点列表', () => {
  const page = makePage();
  page.onLoad({ id: '42' });
  const detailRequest = requestByUrl('/api/topic/info-to-user');
  detailRequest.success({
    code: '200',
    data: { id: 42, name: '夜航计划', productType: 2, chaptersList: [] },
  });

  const mineRequest = requestByUrl('/api/merchant/chapter-node/mine');
  assert.ok(mineRequest, '页面加载链必须主动读取节点，不能等提交回调刷新');
  assert.equal(mineRequest.method, 'POST');
  assert.deepEqual(mineRequest.data, { topicId: 42 });
  assert.ok(requestByUrl('/api/merchant/chapter-application/mine'),
    '页面重进必须同时刷新申请审核态和 offerActive 冻结事实');
});

test('我的节点只渲染 CmsTopicNode 真字段或页面明确派生字段', () => {
  const referenced = Array.from(WXML.matchAll(/chapterNode\.([A-Za-z][A-Za-z0-9]*)/g), match => match[1]);
  assert.ok(referenced.length > 0, '我的节点区必须真实消费节点字段，不能让契约空转');
  const cmsTopicNodeFields = new Set(
    Array.from(CMS_TOPIC_NODE.matchAll(/private\s+(?:[\w.<>]+)\s+([A-Za-z][A-Za-z0-9]*)\s*;/g), match => match[1]),
  );
  cmsTopicNodeFields.add('auditLabel');
  const phantom = referenced.filter(field => !cmsTopicNodeFields.has(field));
  assert.deepEqual(phantom, [], `我的节点渲染了 CmsTopicNode 不存在的字段: ${phantom.join(', ')}`);
});

test('三档 offer payload 只携带章节供给字段，不接受服务端接管字段', () => {
  const page = makePage();

  assert.deepEqual(page.buildChapterOfferPayload({
    chapterId: 11, termsMode: 'TRAFFIC', quotaTotal: '30',
    merchantId: 999, topicId: 888, status: 0, quotaUsed: 27,
  }), { chapterId: 11, termsMode: 'TRAFFIC' });
  assert.deepEqual(page.buildChapterOfferPayload({
    chapterId: 12, termsMode: 'REVSHARE', perHeadFee: '8.50', quotaTotal: '20',
    merchantId: 999, topicId: 888, status: 0, quotaUsed: 19,
  }), { chapterId: 12, termsMode: 'REVSHARE', perHeadFee: 8.5 });
});

function pausedReplacementPage(offer) {
  const page = makePage();
  page.data.info = { lifecycle: 3, status: 1, userStatus: 0 };
  page.data.myChapterApplications = [Object.assign({ id: 11, chapterId: 124, chapterName: '第一章',
    status: 1, termsMode: 'PERK', replacementOfOfferId: 5, offerState: 'NONE', offerActive: false }, offer)];
  page.refreshMyChapterApplications();
  return page;
}

function pendingReplacementPage(info, offer) {
  const page = pausedReplacementPage(offer);
  page.data.info = Object.assign({ lifecycle: 7, status: 0, userStatus: 0,
    productType: 2, publishMode: 'pro', delFlag: 0 }, info);
  page.refreshMyChapterApplications();
  return page;
}

test('待审补位仅在真实专业漫游与当前已接受本人申请中解锁既有节点表单', () => {
  const page = pendingReplacementPage();
  assert.equal(page.data.myChapterApplications[0].canConfigureNode, true);
  assert.equal(page.data.myChapterApplications[0].canSubmitOffer, true);
  page.openChapterNodeForm({ currentTarget: { dataset: { chapterId: 124 } } });
  assert.equal(page.data.chapterNodeFormVisible, true);
  page.onChapterNodeFormSubmit({ detail: { chapterId: 124, name: '花店补位点', templateId: 6003,
    address: '上海市黄浦区样板花店3', longitude: '121.5', latitude: '31.26' } });
  assert.equal(requestByUrl('/api/merchant/chapter-node/submit').data,
    JSON.stringify({ chapterId: 124, templateId: 6003, name: '花店补位点',
      address: '上海市黄浦区样板花店3', longitude: '121.5', latitude: '31.26', xpValue: null }));
  assert.equal(requestByUrl('/api/merchant/chapter-application/apply'), undefined);
  const pending = pendingReplacementPage({}, { offerId: 11, offerState: 'INACTIVE' });
  assert.equal(pending.data.myChapterApplications[0].canConfigureNode, true);
  assert.equal(pending.data.myChapterApplications[0].canSubmitOffer, false);
});

test('待审补位缺少任何服务端事实、普通主题、未接受或供给终态均保持只读', () => {
  for (const patch of [{ status: null }, { status: undefined }, { status: '' }, { status: 1 },
    { userStatus: undefined }, { userStatus: null }, { userStatus: '' }, { userStatus: false }, { userStatus: 1 },
    { delFlag: undefined }, { delFlag: null }, { delFlag: '' }, { delFlag: false }, { delFlag: 1 }, { delFlag: 2 },
    { productType: undefined }, { productType: 1 }, { publishMode: undefined }, { publishMode: 'simple' },
    { lifecycle: null }, { lifecycle: 0 }, { lifecycle: 3 }, { circleThemeCode: 'CIRCLE' }]) {
    const page = pendingReplacementPage(patch);
    assert.equal(page.data.myChapterApplications[0].canConfigureNode, false, JSON.stringify(patch));
    assert.equal(page.data.myChapterApplications[0].canSubmitOffer, false, JSON.stringify(patch));
  }
  for (const patch of [{ status: 0 }, { status: 2 }, { offerState: 'WITHDRAWN' },
    { offerState: 'EXPIRED', offerId: 11 }, { offerState: 'ACTIVE', offerId: 11, offerActive: true },
    { offerState: 'INACTIVE', offerId: 11, offerWithdrawReason: '已退出' }]) {
    assert.equal(pendingReplacementPage({}, patch).data.myChapterApplications[0].canConfigureNode,
      false, JSON.stringify(patch));
  }
});

test('待审补位从既有本人详情补读精确产品、模式与删除标记，未知不猜可编辑', () => {
  const page = pendingReplacementPage({ productType: undefined, publishMode: undefined, delFlag: undefined });
  Object.assign(page.data, { topicId: 42, chapterProject: true, operationScope: 'MERCHANT' });
  assert.equal(page.data.myChapterApplications[0].canConfigureNode, false);
  page.loadChapterProjectLifecycle();
  const detail = requestByUrl('/api/topic/info-to-merchant');
  assert.deepEqual(detail.data, { id: 42, scope: 'MERCHANT' });
  detail.success({ code: 200, data: { id: 42, lifecycle: 7, status: 0, userStatus: 0,
    productType: 2, publishMode: 'pro', delFlag: 0 } });
  assert.equal(page.data.myChapterApplications[0].canConfigureNode, true);
  page.loadChapterProjectLifecycle();
  requests.at(-1).success({ code: 200, data: { id: 42, lifecycle: 7, status: 0, userStatus: 0, delFlag: null } });
  assert.equal(page.data.info.productType, 2, '部分响应不覆盖已确认的产品事实');
  assert.equal(page.data.myChapterApplications[0].canConfigureNode, false, '显式未知标记必须撤销编辑入口');
  page.data.info.delFlag = 0;
  page.loadChapterProjectLifecycle();
  requests.at(-1).success({ code: 200, data: { id: 42, lifecycle: 7, status: 0, userStatus: 0 } });
  assert.equal(page.data.myChapterApplications[0].canConfigureNode, false, '最新详情省略删除标记不能沿用旧0解锁');
});

test('暂停开售的已批准补位可配置节点；待审供给不能重复提交，审核生效后冻结', () => {
  const page = pausedReplacementPage();
  assert.equal(page.data.myChapterApplications[0].canConfigureNode, true);
  assert.equal(page.data.myChapterApplications[0].canSubmitOffer, true);
  page.openChapterNodeForm({ currentTarget: { dataset: { chapterId: 124 } } });
  assert.equal(page.data.chapterNodeFormMode, 'node');
  assert.equal(page.data.chapterNodeFormVisible, true);
  const pending = pausedReplacementPage({ offerId: 11, offerState: 'INACTIVE' });
  assert.equal(pending.data.myChapterApplications[0].canConfigureNode, true);
  assert.equal(pending.data.myChapterApplications[0].offerPendingReview, true);
  assert.equal(pending.data.myChapterApplications[0].canSubmitOffer, false);
  pending.openChapterOfferForm({ currentTarget: { dataset: { chapterId: 124 } } });
  assert.equal(pending.data.chapterNodeFormVisible, false);
  pending.submitApprovedChapterOffer({ chapterId: 124, termsMode: 'PERK', perkTemplateId: 3003, quotaTotal: 17 });
  assert.equal(requestByUrl('/api/coop/offer/enroll'), undefined);
  const active = pausedReplacementPage({ offerId: 11, offerState: 'ACTIVE', offerActive: true });
  assert.equal(active.data.myChapterApplications[0].canConfigureNode, false);
  active.openChapterNodeForm({ currentTarget: { dataset: { chapterId: 124 } } });
  assert.equal(active.data.chapterNodeFormVisible, false);
});

test('补位例外不解冻普通商家、终态、营业中、未知状态或退出供给', () => {
  for (const patch of [{ userStatus: 1 }, { lifecycle: 5 }, { lifecycle: null }, { status: 0 },
      { userStatus: null }, { circleThemeCode: 'CIRCLE' }]) {
    const page = pausedReplacementPage();
    Object.assign(page.data.info, patch);
    page.refreshMyChapterApplications();
    assert.equal(page.data.myChapterApplications[0].canConfigureNode, false, JSON.stringify(patch));
    assert.equal(page.data.myChapterApplications[0].canSubmitOffer, false, JSON.stringify(patch));
  }
  for (const patch of [{ replacementOfOfferId: null }, { status: 0 }, { offerState: 'WITHDRAWN' },
      { offerState: 'EXPIRED', offerId: 11 }, { offerState: 'INACTIVE', offerId: 11, offerWithdrawReason: '退出' }]) {
    const page = pausedReplacementPage(patch);
    assert.equal(page.data.myChapterApplications[0].canConfigureNode, false, JSON.stringify(patch));
  }
});

test('补位保存沿既有批准申请直接提交真实地址坐标，不再次 apply 或写供给', () => {
  const page = pausedReplacementPage({ offerId: 11, offerState: 'INACTIVE' });
  page.openChapterNodeForm({ currentTarget: { dataset: { chapterId: 124 } } });
  page.onChapterNodeFormSubmit({ detail: { chapterId: 124, name: '花店补位点', templateId: 6003,
    address: '上海市黄浦区样板花店3', longitude: '121.5', latitude: '31.26' } });
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, '/api/merchant/chapter-node/submit');
  assert.deepEqual(JSON.parse(requests[0].data), { chapterId: 124, templateId: 6003,
    name: '花店补位点', address: '上海市黄浦区样板花店3', longitude: '121.5', latitude: '31.26', xpValue: null });
  requests[0].success({ code: 200 });
  requests[0].complete();
  assert.equal(page.data.chapterNodeFormVisible, false);
  assert.ok(requestByUrl('/api/merchant/chapter-application/mine'));
  assert.equal(requestByUrl('/api/merchant/chapter-application/apply'), undefined);
  assert.equal(requestByUrl('/api/coop/offer/enroll'), undefined);
  const other = pausedReplacementPage();
  other.data.chapterNodeFormMode = 'node'; other.data.selectedChapterId = 999;
  const count = requests.length;
  other.onChapterNodeFormSubmit({ detail: { chapterId: 124, name: '错章', templateId: 6003 } });
  assert.equal(requests.length, count);
});

test('补位供给提交只提示待审核并回读申请，不把提交回执说成已生效', () => {
  const page = pausedReplacementPage();
  page.submitApprovedChapterOffer({ chapterId: 124, termsMode: 'PERK', perkTemplateId: 3003, quotaTotal: 17 });
  const enroll = requestByUrl('/api/coop/offer/enroll');
  assert.ok(enroll);
  enroll.success({ code: 200, data: { id: 11, status: 0 } });
  assert.equal(toasts.at(-1).title, '供给已提交，等待审核');
  assert.ok(requestByUrl('/api/merchant/chapter-application/mine'));
  assert.equal(page.data.chapterNodeSubmitting, false);
  assert.match(WXML, /application\.offerPendingReview/);
});

test('承接管理组件的配置节点动作通过现有白名单派发，仍留在join管理视图', () => {
  const page = pausedReplacementPage();
  page.data.fromMerchantJoin = false; page.data.chapterProject = true; page.data.role = 'join';
  page.onJoinAct({ detail: { act: 'openChapterNodeForm', dataset: { chapterId: 124 } } });
  assert.equal(page.data.chapterNodeFormVisible, true);
  assert.equal(page.data.chapterNodeFormMode, 'node');
  assert.equal(page.data.role, 'join');
  assert.equal(page.data.fromMerchantJoin, false);
  const source = fs.readFileSync(path.resolve(__dirname, '../../pages/topic/components/project-join/index.wxml'), 'utf8');
  assert.match(source, /data-act="openChapterNodeForm"/);
  assert.match(source, /application\.offerPendingReview/);
});


test('普通生效供给可退出，圈层供给沿用暂停入口，输入和确认取消都不写入', () => {
  const page = makePage();
  page.data.info = { lifecycle: 3 };
  page.data.myChapterApplications = [
    { id: 1, offerId: 11, status: 1, offerActive: true },
    { id: 2, offerId: 12, status: 1, offerActive: true, circleThemeCode: 'LOCAL' },
    { id: 3, offerId: null, status: 1, offerActive: true },
  ];
  page.refreshMyChapterApplications();
  assert.ok(page.data.myChapterApplications.every(row => row.canConfigureNode === false));
  assert.deepEqual(page.data.myChapterApplications.map(row => row.canWithdrawOffer), [true, false, false]);
  page.withdrawChapterOffer({ currentTarget: { dataset: { offerId: 12 } } });
  assert.equal(modals.length, 0);
  page.withdrawChapterOffer({ currentTarget: { dataset: { offerId: 11 } } });
  assert.equal(modals[0].content, '', '后果说明不能当作默认退出原因');
  modals[0].success({ confirm: true, content: '  ' });
  assert.equal(requests.length, 0);
  assert.equal(modals.length, 2, '空原因应留在输入步骤');
  modals[1].success({ cancel: true });
  assert.equal(requests.length, 0);
  page.withdrawChapterOffer({ currentTarget: { dataset: { offerId: 11 } } });
  modals[2].success({ confirm: true, content: ' 店铺维修 ' });
  assert.equal(modals[3].dangerKey, 'merchant.chapterOffer.withdraw');
  assert.deepEqual(modals[3].dangerParams, { reason: '店铺维修' });
  assert.match(modals[3].content, /暂停新增报名/);
  modals[3].success({ cancel: true });
  assert.equal(requests.length, 0);
});

test('普通供给退出提交真实理由，重复确认不重发，失败保留入口，成功重新读取', () => {
  const page = makePage();
  page.data.info = { lifecycle: 3 };
  page.data.topicId = 42;
  page.data.myChapterApplications = [{ id: 1, offerId: 11, status: 1, offerActive: true }];
  page.refreshMyChapterApplications();
  page.withdrawChapterOffer({ currentTarget: { dataset: { offerId: 11 } } });
  modals[0].success({ confirm: true, content: ' 店铺维修 ' });
  modals[1].success({ confirm: true });
  modals[1].success({ confirm: true });
  assert.equal(requests.length, 1);
  const request = requests[0];
  assert.equal(request.url, '/api/coop/offer/withdraw');
  assert.deepEqual(JSON.parse(request.data), { offerId: 11, reason: '店铺维修' });
  request.success({ code: '500', msg: '供给状态已变化' });
  assert.equal(tips.at(-1), '供给状态已变化');
  assert.equal(page.data.myChapterApplications[0].canWithdrawOffer, true);
  request.complete();
  page.withdrawChapterOffer({ currentTarget: { dataset: { offerId: 11 } } });
  modals[2].success({ confirm: true, content: '店铺维修' });
  modals[3].success({ confirm: true });
  requests[1].success({ code: '200', msg: '已退出，报名已暂停' });
  assert.ok(requestByUrl('/api/merchant/chapter-application/mine'));
  requests[1].complete();
  assert.equal(page._chapterOfferWithdrawing, false);
});


test('已退出供给按历史事实只读，原审核通过与退出原因保留且不可重新提交', () => {
  const page = makePage();
  page.data.info = { lifecycle: 1 };
  page.data.myChapterApplications = [{
    id: 71, chapterId: 31, status: 1, source: 1, termsMode: 'PERK',
    offerId: 11, offerActive: false, offerState: 'WITHDRAWN', offerWithdrawReason: '店铺维修',
    canSubmitOffer: true, canWithdraw: true, canWithdrawOffer: true,
  }];
  page.refreshMyChapterApplications();
  const row = page.data.myChapterApplications[0];
  assert.equal(row.status, 1, '审核事实不改为驳回');
  assert.equal(row.statusLabel, '已退出供给');
  assert.equal(row.offerWithdrawReason, '店铺维修');
  assert.equal(row.offerWithdrawn, true);
  assert.equal(row.offerReadOnly, true);
  assert.equal(row.canConfigureNode, false);
  assert.match(row.nodeReadOnlyReason, /历史记录/);
  assert.equal(row.canSubmitOffer, false);
  assert.equal(row.canWithdraw, false);
  assert.equal(row.canWithdrawOffer, false);
  assert.equal(row.canReconfirmCircleSupply, false);
});

test('退出状态优先于残留 active 标志，活供给和未形成供给的邀请仍沿原入口', () => {
  const page = makePage();
  page.data.myChapterApplications = [
    { id: 1, chapterId: 31, status: 1, offerId: 11, offerActive: true, offerState: 'WITHDRAWN', termsMode: 'TRAFFIC' },
    { id: 2, chapterId: 32, status: 1, offerId: 12, offerActive: true, termsMode: 'TRAFFIC' },
    { id: 3, chapterId: 33, status: 1, source: 1, offerActive: false, termsMode: 'TRAFFIC' },
  ];
  page.refreshMyChapterApplications();
  const [withdrawn, active, invitation] = page.data.myChapterApplications;
  assert.equal(withdrawn.canWithdrawOffer, false);
  assert.equal(withdrawn.canSubmitOffer, false);
  assert.equal(active.offerReadOnly, true);
  assert.equal(active.canWithdrawOffer, true);
  assert.equal(active.offerWithdrawn, false);
  assert.equal(invitation.canWithdraw, false);
  assert.equal(invitation.canSubmitOffer, true);
  assert.equal(invitation.offerReadOnly, false);
});

test('退出供给即使未刷新映射也不能直达供给表单或撤回申请', () => {
  const page = makePage();
  const dc = attachDangerConfirm(page, { confirmHandler: 'onConfirmWithdrawApplication' });
  page.data.myChapterApplications = [{
    id: 71, chapterId: 31, status: 1, source: 1, termsMode: 'TRAFFIC',
    offerState: 'WITHDRAWN', offerActive: false, canSubmitOffer: true, canWithdraw: true,
  }];
  page.openChapterOfferForm({ currentTarget: { dataset: { chapterId: 31 } } });
  assert.equal(page.data.chapterNodeFormVisible, false);
  page.withdrawChapterApplication({ currentTarget: { dataset: { applicationId: 71 } } });
  assert.equal(dc.opens.length, 0);
  assert.ok(!requestByUrl('/api/merchant/chapter-application/withdraw'));
});
