const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('../helpers/ui-sandbox-vm.js');

function load() {
  const requests = [];
  let config;
  const context = {
    getApp: () => ({ globalData: {}, getImgUrl: x => x, getPageSize: () => 10,
      getTotalPage: (total, size) => Math.ceil(total / size),
      getRequestErrorMessage: (r, fallback) => r.msg || fallback,
      sendRequest: request => requests.push(request) }),
    Page: page => { config = page; }, setTimeout: () => 1,
    wx: { showToast() {} }, console,
  };
  vm.createContext(context);
  vm.runInContext(fs.readFileSync(path.resolve(__dirname, '../../subpackageMember/signup/index.js'), 'utf8'), context);
  const page = Object.assign({}, config, { data: JSON.parse(JSON.stringify(config.data)) });
  page.setData = patch => Object.assign(page.data, patch);
  return { page, requests };
}
function reply(request, rows, total = rows.length) {
  request.success({ code: 200, data: request.url === '/api/team/my' ? rows : { rows, total } });
  if (request.complete) request.complete();
}
const topic = id => ({ id, registrationStatus: 2, cmsTopic: { id: 10, name: '路线' } });
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
  page.onShow(); assert.equal(requests.length, 6);
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


test('取消焦点消失后选择第二有效票，下一次返页不被残留focus阻碍', () => {
  const { page, requests } = load(); page.onLoad({ focusId: '115' }); page.onShow();
  reply(requests[0], [activity(115)]); reply(requests[1], []); reply(requests[2], []);
  page.onHide(); page.onShow();
  reply(requests[3], []); reply(requests[4], [topic(100), topic(101)]); reply(requests[5], []);
  assert.equal(page._focusId, '115');
  page.onTicketSwiperChange({ detail: { current: 1 } });
  assert.equal(page.data.ticketList[page.data.currentSwiperTicket].id, 101);
  page.onHide(); page.onShow();
  assert.equal(page._focusId, '101');
  reply(requests[6], []); reply(requests[7], [topic(100), topic(101)]); reply(requests[8], []);
  assert.equal(page.data.ticketList[page.data.currentSwiperTicket].id, 101);
  assert.equal(page._focusId, '');
});
