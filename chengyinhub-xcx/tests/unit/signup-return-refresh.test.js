const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('../helpers/ui-sandbox-vm.js');

function load({ clock = false } = {}) {
  let now = 0, timerId = 0;
  const timers = new Map();
  const requests = [];
  let config, owner = 'member-a';
  const navigations = [];
  const context = {
    getApp: () => ({ globalData: {}, getUserID: () => owner, getImgUrl: x => x, getPageSize: () => 10,
      getTotalPage: (total, size) => Math.ceil(total / size),
      getRequestErrorMessage: (r, fallback) => r.msg || fallback,
      sendRequest: request => requests.push(request) }),
    Page: page => { config = page; },
    setTimeout(fn, delay) {
      if (!clock) return 1;
      timers.set(++timerId, { fn, at: now + Number(delay || 0) }); return timerId;
    },
    clearTimeout(id) { timers.delete(id); },
    wx: { showToast() {}, navigateTo(options) { navigations.push(options.url); } }, console,
  };
  vm.createContext(context);
  vm.runInContext(fs.readFileSync(path.resolve(__dirname, '../../subpackageMember/signup/index.js'), 'utf8'), context);
  const page = Object.assign({}, config, { data: JSON.parse(JSON.stringify(config.data)) });
  page.setData = patch => {
    for (const [key, value] of Object.entries(patch)) {
      const indexed = /^(\w+)\[(\d+)\]$/.exec(key);
      if (indexed) page.data[indexed[1]][Number(indexed[2])] = value;
      else page.data[key] = value;
    }
  };
  return { page, requests, navigations, owner(value) { owner = value; }, advance(ms) {
    const end = now + ms;
    for (let steps = 0; steps < 1000; steps++) {
      const next = Array.from(timers.entries()).filter(([, t]) => t.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
      if (!next) { now = end; return; }
      timers.delete(next[0]); now = next[1].at; next[1].fn();
    }
    throw new Error('timer loop');
  } };
}
function reply(request, rows, total = rows.length) {
  request.success({ code: 200, data: request.url === '/api/team/my' ? rows : { rows, total } });
  if (request.complete) request.complete();
}
const topic = id => ({ id, paymentStatus: 2, registrationStatus: 2, cmsTopic: { id: 10, name: '路线' } });
const activity = id => ({ id, paymentStatus: 2, registrationStatus: 2, cmsActivity: { id: 63, name: '场次', topicId: 10 } });   // 第十二轮 k2:有游戏的场次票(没有游戏的票会直接签入场码,见 k2-no-game-ticket)

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

function assertFrontMatchesSelection(page, id) {
  const selected = page.data.ticketList[page.data.currentSwiperTicket];
  assert.equal(String(selected.id), String(id));
  const front = page.data.deckPoses.flatMap((pose, index) =>
    /opacity: 1;/.test(pose) && /z-index: 30;/.test(pose) ? [index] : []);
  assert.deepEqual(Array.from(front), [page.data.currentSwiperTicket],
    'the selected ticket must be the only visible foreground card');
  assert.equal(page.data.ticketList[front[0]].wTitle, selected.wTitle);
  if (selected.wImg) assert.equal(page.data.wbg[page.data.wbgOn], selected.wImg);
}

function previousCard(h) {
  h.page.onDeckStart({ touches: [{ clientX: 0, clientY: 0 }] });
  h.page.onDeckMove({ touches: [{ clientX: 200, clientY: 0 }] });
  h.page.onDeckEnd();
  h.advance(600);
}

for (const explicit of [false, true]) for (const topicFirst of [false, true]) {
  test('retained wallet restores the visual card and action identity: explicit=' + explicit + ', topicFirst=' + topicFirst, () => {
    const h = load({ clock: true });
    const rows = Array.from({ length: 19 }, (_, i) => ({ ...topic(20000 + i),
      cmsTopic: { id: 30000 + i, name: i === 18 ? 'Finished route' : 'Other route ' + i, imgUrl: 'cover-' + i } }));
    const wanted = rows[18];
    h.page.onLoad(explicit ? { focusId: String(wanted.id) } : {});
    h.page.onShow();
    reply(h.requests[1], rows);
    const entrance = h.page.data.deckPoses[h.page.data.currentSwiperTicket];
    assert.match(entrance, /translate\(0px,560px\)/, 'normal card entrance is still played');
    reply(h.requests[0], [activity(40000)]);
    assert.equal(h.page.data.deckPoses[h.page.data.currentSwiperTicket], entrance,
      'the second list must not interrupt entrance of the same card');
    reply(h.requests[2], []);
    h.advance(1300);
    if (!explicit) { previousCard(h); previousCard(h); }
    assertFrontMatchesSelection(h.page, wanted.id);
    h.page.onHide();
    h.page.onShow();
    assert.equal(h.page.data.currentSwiperTicket, 0, 'exercise the actual retained-page reset');
    const refreshed = rows.map(row => row.id === wanted.id ? { ...row, topicCompleted: true } : row);
    const batch = h.requests.slice(3);
    const first = topicFirst ? 1 : 0, second = 1 - first;
    const payloads = [[activity(40000)], refreshed];
    reply(batch[first], payloads[first]);
    if (topicFirst) assertFrontMatchesSelection(h.page, wanted.id);
    reply(batch[second], payloads[second]);
    reply(batch[2], [{ id: 501, ownerType: 1, ownerId: wanted.cmsTopic.id, status: 1 }]);
    assertFrontMatchesSelection(h.page, wanted.id);
    assert.equal(h.page.data.ticketList[h.page.data.currentSwiperTicket].topicCompleted, true);
    assert.equal(h.page.data.currentTeam.id, 501);
    assert.equal(h.page.data.qrState, 'idle');
    h.page.goTicket({ currentTarget: { dataset: { index: h.page.data.currentSwiperTicket } } });
    assert.match(h.navigations.at(-1), new RegExp('registrationId=' + wanted.id + '(?:&|$)'));
    assert.match(h.navigations.at(-1), new RegExp('topicId=' + wanted.cmsTopic.id + '(?:&|$)'));
  });
}

for (const topicFirst of [false, true]) test('settled explicit focus lookup relays out the returned card: topicFirst=' + topicFirst, () => {
  const h = load({ clock: true });
  h.page.onLoad({ focusId: '115' }); h.page.onShow();
  const first = topicFirst ? 1 : 0, second = 1 - first;
  const payloads = [[activity(11)], [topic(12)]];
  reply(h.requests[first], payloads[first]); reply(h.requests[second], payloads[second]); reply(h.requests[2], []);
  h.advance(1300);
  const info = h.requests.find(r => r.url === '/api/registration/info');
  assert.ok(info);
  info.success({ code: 200, data: { ...topic(115), ownerType: 1, memberId: 'member-a' } });
  assert.equal(h.page.data.focusState, 'ready');
  assertFrontMatchesSelection(h.page, 115);
});

test('focus lookup preserves no-game QR and team identity on the actual Page', () => {
  const h = load({ clock: true }); h.page.onLoad({ focusId: '115' });
  const info = settleFirstPages(h); h.advance(1300);
  info.success({ code: 200, data: { ...activity(115), ownerType: 2, memberId: 'member-a',
    cmsActivity: { id: 63, name: 'Entry only' } } });
  assertFrontMatchesSelection(h.page, 115);
  assert.equal(h.page.data.flipped, true);
  const qr = h.requests.at(-1);
  assert.equal(qr.url, '/api/verify/dyncode/issue');
  assert.deepEqual({ ...qr.data }, { registrationId: '115' });
  h.page.loadMyTeams();
  reply(h.requests.at(-1), [{ id: 503, ownerType: 2, ownerId: 63, status: 1 }]);
  assert.equal(h.page.data.currentTeam.id, 503);
});

for (const delay of [0, 40]) test('late focus during card entrance cannot be overwritten by the old card timer: delay=' + delay, () => {
  const h = load({ clock: true }); h.page.onLoad({ focusId: '115' });
  const first = delay === 0 ? 0 : 1, second = 1 - first;
  const payloads = [[activity(11)], [topic(12)]];
  reply(h.requests[first], payloads[first]); reply(h.requests[second], payloads[second]); reply(h.requests[2], []);
  const info = h.requests.find(r => r.url === '/api/registration/info');
  h.advance(delay);
  info.success({ code: 200, data: { ...topic(115), ownerType: 1, memberId: 'member-a' } });
  assertFrontMatchesSelection(h.page, 115);
  h.advance(1300);
  assertFrontMatchesSelection(h.page, 115);
});
