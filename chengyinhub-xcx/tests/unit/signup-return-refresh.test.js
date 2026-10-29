const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('../helpers/ui-sandbox-vm.js');

function load() {
  const requests = [];
  let config, owner = 'member-a';
  const navigations = [];
  const context = {
    getApp: () => ({ globalData: {}, getUserID: () => owner, getImgUrl: x => x, getPageSize: () => 10,
      getTotalPage: (total, size) => Math.ceil(total / size),
      getRequestErrorMessage: (r, fallback) => r.msg || fallback,
      sendRequest: request => requests.push(request) }),
    Page: page => { config = page; }, setTimeout: () => 1,
    wx: { showToast() {}, navigateTo(options) { navigations.push(options.url); } }, console,
  };
  vm.createContext(context);
  vm.runInContext(fs.readFileSync(path.resolve(__dirname, '../../subpackageMember/signup/index.js'), 'utf8'), context);
  const page = Object.assign({}, config, { data: JSON.parse(JSON.stringify(config.data)) });
  page.setData = patch => Object.assign(page.data, patch);
  return { page, requests, navigations, owner(value) { owner = value; } };
}
function reply(request, rows, total = rows.length) {
  request.success({ code: 200, data: request.url === '/api/team/my' ? rows : { rows, total } });
  if (request.complete) request.complete();
}
const topic = id => ({ id, paymentStatus: 2, registrationStatus: 2, cmsTopic: { id: 10, name: '路线' } });
const activity = id => ({ id, paymentStatus: 2, registrationStatus: 2, cmsActivity: { id: 63, name: '场次' } });

test('首show不重复加载；隐藏取消后返回重读两类票与队伍，移除旧票', () => {
  const { page, requests } = load();
  page.onLoad({ focusId: '115' }); page.onShow();
  assert.equal(requests.length, 3);
  reply(requests[0], [activity(115)]); reply(requests[1], [topic(100)]);
  reply(requests[2], [{ id: 7, ownerType: 2, ownerId: 63, status: 1 }]);
  assert.equal(page.data.ticketList.length, 2);
  assert.equal(page.data.ticketList[page.data.currentSwiperTicket].id, 115);
  assert.equal(page.data.currentTeam.id, 7);
  page.onHide(); page.onShow();
  assert.equal(requests.length, 6);
  assert.deepEqual(requests.slice(3).map(r => r.url).sort(), ['/api/registration/list', '/api/registration/list', '/api/team/my']);
  reply(requests[3], []); reply(requests[4], [topic(100)]); reply(requests[5], []);
  assert.deepEqual(Array.from(page.data.ticketList, t => t.id), [100]);
  assert.equal(page.data.walletState, 'ready');
  assert.equal(page.data.currentTeam, null);
  assert.equal(page.data.focusState, 'loading');
  assert.equal(requests[6].url, '/api/registration/info');
  requests[6].success({ code: 404 });
  assert.equal(page.data.focusState, 'missing');
  page.onShow(); assert.equal(requests.length, 7);
});

test('隐藏前票/队伍旧成功与失败complete逆序晚到不覆盖新终态，卸载亦失效', () => {
  const { page, requests } = load(); page.onLoad({});
  page.onHide(); page.onShow();
  reply(requests[3], []); reply(requests[4], [topic(101)]); reply(requests[5], []);
  const before = JSON.stringify(page.data);
  reply(requests[0], [activity(115)]); reply(requests[1], [topic(100)]);
  reply(requests[2], [{ id: 7, ownerType: 1, ownerId: 10, status: 1 }]);
  requests[0].fail({ errMsg: '旧失败' }); requests[0].complete();
  assert.equal(JSON.stringify(page.data), before);
  page.getTopicList(); page.getActivityList(); page.loadMyTeams();
  const late = requests.slice(6); const beforeUnload = JSON.stringify(page.data); page.onUnload();
  late.forEach(r => reply(r, r.url === '/api/team/my' ? [] : [topic(999)]));
  late.filter(r => r.fail).forEach(r => { r.fail({ errMsg: '卸载后失败' }); r.complete(); });
  assert.equal(JSON.stringify(page.data), beforeUnload);
});

test('刷新后两类票继续分页追加与单支重试，focus仍定位目标', () => {
  const { page, requests } = load(); page.onLoad({}); page.onShow();
  reply(requests[0], [activity(115)], 20); reply(requests[1], [topic(100)], 20); reply(requests[2], []);
  assert.equal(page.data.hasMore, true);
  page.setData({ page_no: 2 }); page.getTopicList(); page.getActivityList();
  assert.equal(requests[3].data.pageNum, 2); assert.equal(requests[4].data.pageNum, 2);
  reply(requests[3], [topic(101)], 20); reply(requests[4], [activity(116)], 20);
  assert.deepEqual(Array.from(page.data.ticketList, t => t.id), [100, 101, 115, 116]);
  assert.equal(page.data.hasMore, false);
  page._focusId = '116'; page._tryFocusTicket(); assert.equal(page.data.currentSwiperTicket, 3);
  page.onHide(); page.onShow();
  assert.equal(requests[5].data.pageNum, 1); assert.equal(requests[6].data.pageNum, 1);
  reply(requests[5], [activity(116)]); requests[6].fail({ errMsg: '路线暂失败' }); requests[6].complete(); reply(requests[7], []);
  assert.equal(page.data.ticketList[page.data.currentSwiperTicket].id, 116);
  page.retryWallet(); assert.equal(requests.length, 9); assert.equal(requests[8].data.owner_type, 1);
  reply(requests[8], [topic(100)]);
  assert.deepEqual(Array.from(page.data.ticketList, t => t.kind), ['topic', 'activity']);
  assert.equal(page.data.ticketList[page.data.currentSwiperTicket].id, 116);
});


test('指定原票消失时明确未定位，不把用户滑到的其他票当作原票', () => {
  const { page, requests, navigations } = load(); page.onLoad({ focusId: '115' }); page.onShow();
  reply(requests[0], [activity(115)]); reply(requests[1], []); reply(requests[2], []);
  page.onHide(); page.onShow();
  reply(requests[3], []); reply(requests[4], [topic(100), topic(101)]); reply(requests[5], []);
  assert.equal(requests[6].url, '/api/registration/info'); requests[6].success({ code: 404 });
  assert.equal(page._focusId, '115'); assert.equal(page.data.focusState, 'missing');
  page.setData({ currentSwiperTicket: 1 }); // v13 卡组:用户把别的票甩到了最前
  page.goTicket({ currentTarget: { dataset: { index: 1 } } }); assert.equal(navigations.length, 0);
  page.onHide(); page.onShow(); assert.equal(page._focusId, '115');
});

const settleFirstPages = (h, total = 20) => {
  reply(h.requests[0], [activity(11)], total);
  reply(h.requests[1], [topic(12)], total);
  reply(h.requests[2], []);
  return h.requests.find(r => r.url === '/api/registration/info');
};

test('原票在次页仍通过既有本人详情接口精确定位，不遍历或信任total', () => {
  for (const total of [20, Infinity, NaN, 'bad', 0]) {
    const h = load(); h.page.onLoad({ focusId: '9007199254740993' });
    const info = settleFirstPages(h, total);
    assert.ok(info); assert.deepEqual({ ...info.data }, { id: '9007199254740993' });
    assert.equal(h.page.data.focusState, 'loading');
    h.page.goTicket({ currentTarget: { dataset: { index: 0 } } }); assert.equal(h.navigations.length, 0);
    info.success({ code: 200, data: { ...topic('9007199254740993'), ownerType: 1, memberId: 'member-a' } });
    assert.equal(h.page.data.focusState, 'ready');
    assert.equal(h.page.data.ticketList[h.page.data.currentSwiperTicket].id, '9007199254740993');
    assert.equal(h.requests.filter(r => r.url === '/api/registration/list').length, 2);
    const before = JSON.stringify(h.page.data); info.success({ code: 200, data: topic(999) });
    assert.equal(JSON.stringify(h.page.data), before);
  }
});

for (const result of ['missing', 'wrong-id', 'wrong-owner', 'malformed', 'cancelled']) test('原票查读 ' + result + ' 不用其他票冒充，可只读重试', () => {
  const h = load(); h.page.onLoad({ focusId: '115' }); const info = settleFirstPages(h);
  const data = { ...activity(115), ownerType: 2, memberId: 'member-a' };
  if (result === 'wrong-id') data.id = 116;
  if (result === 'wrong-owner') data.memberId = 'member-b';
  if (result === 'cancelled') data.registrationStatus = 3;
  info.success(result === 'missing' ? { code: 404 } : { code: 200, data: result === 'malformed' ? [] : data });
  assert.equal(h.page.data.focusState, 'missing');
  h.page.goTicket({ currentTarget: { dataset: { index: 0 } } }); assert.equal(h.navigations.length, 0);
  h.page.retryFocus(); const retry = h.requests.at(-1);
  assert.equal(retry.url, '/api/registration/info');
  retry.success({ code: 200, data: { ...activity(115), ownerType: 2, memberId: 'member-a' } });
  assert.equal(h.page.data.focusState, 'ready');
  assert.equal(h.page.data.ticketList[h.page.data.currentSwiperTicket].id, 115);
});

for (const phase of ['owner', 'hide', 'unload']) test('原票详情与列表 ' + phase + ' 后的旧成功/失败不能污染新页', () => {
  const h = load(); h.page.onLoad({ focusId: '115' }); const info = settleFirstPages(h);
  if (phase === 'owner') h.owner('member-b');
  if (phase === 'hide') h.page.onHide();
  if (phase === 'unload') h.page.onUnload();
  const before = JSON.stringify(h.page.data);
  info.success({ code: 200, data: { ...activity(115), ownerType: 2 } }); info.fail({});
  reply(h.requests[0], [activity(999)]); h.requests[0].fail({}); h.requests[0].complete();
  assert.equal(JSON.stringify(h.page.data), before);
  h.page.goTicket({ currentTarget: { dataset: { index: 0 } } }); assert.equal(h.navigations.length, 0);
});

test('详情hide/show竞态与两路逆序返回不重复插票；不再轮询内存', () => {
  const h = load(); h.page.onLoad({ focusId: '115' }); const oldInfo = settleFirstPages(h);
  h.page.onHide(); h.page.onShow();
  const batch = h.requests.slice(4);
  reply(batch[1], [topic(12), topic('12')], Infinity); reply(batch[0], [activity(11)]); reply(batch[2], []);
  const info = h.requests.at(-1); assert.equal(info.url, '/api/registration/info');
  oldInfo.success({ code: 200, data: { ...activity(115), ownerType: 2 } });
  assert.equal(h.page.data.focusState, 'loading');
  info.success({ code: 200, data: { ...activity(115), ownerType: 2 } });
  assert.equal(h.page.data.ticketList.filter(x => String(x.id) === '12').length, 1);
  assert.equal(h.page.data.ticketList[h.page.data.currentSwiperTicket].id, 115);
  const wxml = fs.readFileSync(path.resolve(__dirname, '../../subpackageMember/signup/index.wxml'), 'utf8');
  assert.match(wxml, /未定位到原报名凭证/);
  assert.match(wxml, /walletState === 'ready' && \(!focusState \|\| focusState === 'ready'\)/);
});
