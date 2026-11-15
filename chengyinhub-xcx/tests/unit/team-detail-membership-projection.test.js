'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
const { createRequire } = require('node:module');
const { platform, receipt } = require('./helpers/team-event-platform');
const FILE = path.resolve(__dirname, '../../pages/team/detail/index.js');
const WXML = FILE.replace('.js', '.wxml');
const copy = value => JSON.parse(JSON.stringify(value));
const teamId = '9007199254740993';
const members = [
  { memberId: '71', memberName: '当前队长', memberAvatar: '', role: 1 },
  { memberId: '72', memberName: '当前队员', memberAvatar: '', role: 0 },
];
const applicant = id => ({ memberId: id, memberName: '申请人' + id, memberAvatar: '', applyMessage: '一起出发', applyExpireTime: '2099-01-01 12:00:00' });
function mount(source = fs.readFileSync(FILE, 'utf8')) {
  let definition, user = '71', token = 'isolated-a';
  const requests = [], opened = [];
  const app = { globalData: {}, getUserID: () => user, getAuthorization: () => token,
    getRequestErrorMessage: (res, fallback) => res && res.msg || fallback,
    sendRequest: request => { requests.push(request); return { abort() {} }; } };
  vm.runInNewContext(source, { Page: d => { definition = d; }, getApp: () => app, wx: platform(), console,
    require: id => id.includes('toast') ? () => {} : createRequire(FILE)(id) });
  const page = { ...definition, data: copy(definition.data),
    selectComponent: () => ({ open: (key, params) => opened.push({ key, params }), close() {} }),
    setData(patch, done) {
      for (const [key, value] of Object.entries(patch)) {
        const parts = key.split('.'); let target = this.data;
        for (const part of parts.slice(0, -1)) target = target[part];
        target[parts.at(-1)] = value;
      }
      if (done) done();
    } };
  page.onLoad({ teamId }); page.onShow();
  return { page, requests, opened, account(id, auth) { user = id; token = auth; },
    request: url => requests.filter(r => r.url === url).at(-1),
    count: url => requests.filter(r => r.url === url).length,
    info(overrides = {}) {
      const roster = overrides.members || copy(members);
      const team = { id: teamId, ownerType: 2, ownerId: 101, title: '', status: 0, joinMode: 2,
        joinedCount: roster.length, maxMembers: 4, inviteCode: 'FIXTURE-ONLY', ...overrides.team };
      this.request('/api/team/info').success({ code: 200, data: { team, members: roster, joined: true, leader: true,
        seats: { capacity: 4, occupied: roster.length, available: 4 - roster.length, paidOccupied: roster.length, pendingApplications: 1 }, ...overrides, team } });
    } };
}
function boot(source) { const h = mount(source); h.info(); return h; }
function board(h, roster = members) {
  h.request('/api/orient/team/detail').success({ code: 200, data: {
    team: { teamId, ownerType: 2, ownerId: 101 },
    threshold: { activeCount: roster.length, required: roster.length, ratio: 1 },
    summary: { teamDoneCount: 0, totalTasks: 1 },
    tasks: [{ taskId: '301', name: '任务', completedCount: 1, teamDone: false }],
    members: roster.map(m => ({ memberId: m.memberId, name: m.memberName, avatar: m.memberAvatar, role: m.role, doneTaskIds: [] }))
  } });
}
function kick(h, id = '72') { h.page.kick({ currentTarget: { dataset: { id, name: '队员' } } }); }
function confirmKick(h, id = '72') { return h.page.onConfirmKick({ detail: { params: { id } } }); }
const expanded = () => [...copy(members), { memberId: '74', memberName: '新队员', memberAvatar: '', role: 0 }];

test('成员资格拒绝清申请与派生事实，旧申请成功/失败不能复活', () => {
  for (const late of ['success', 'fail']) {
    const h = boot(), request = h.request('/api/team/applications');
    h.page.openInvite();
    h.request('/api/orient/team/detail').success({ code: 500, errorCode: 'ORIENT_TEAM_NOT_MEMBER' });
    if (late === 'success') request.success({ code: 200, data: [applicant(73)] }); else request.fail({});
    assert.equal(h.page.data.joined, false); assert.equal(h.page.data.leader, false);
    assert.equal(h.page.data.applicantsState, ''); assert.deepEqual(copy(h.page.data.applicants), []);
    assert.deepEqual(copy(h.page.data.openSeats), []); assert.deepEqual(copy(h.page.data.seatChips), []);
    assert.equal(h.page.data.inviteShow, false);
  }
});

test('队长退役清旧申请，新权威info确认的新队长仍能读申请', () => {
  const h = boot(), old = h.request('/api/team/applications');
  old.success({ code: 200, data: [applicant(73)] });
  const changed = [ { ...members[0], role: 0 }, { ...members[1], role: 1 } ];
  board(h, changed);
  assert.equal(h.page.data.leader, false); assert.equal(h.page.data.applicantsState, '');
  old.success({ code: 200, data: [applicant(75)] });
  assert.deepEqual(copy(h.page.data.applicants), []);
  h.info({ members: changed, leader: false });
  assert.equal(h.page.data.applicantsState, '');
  h.account('72', 'isolated-b'); h.page.onShow();
  h.info({ members: changed, leader: true });
  h.request('/api/team/applications').success({ code: 200, data: [applicant(76)] });
  assert.equal(h.page.data.leader, true); assert.equal(h.page.data.applicantsState, 'ready');
  assert.equal(h.page.data.applicants[0].memberId, 76);
});

test('同一正常成员被移交为新队长后，由新info确认并读取申请', () => {
  const h = mount();
  const before = [{ ...members[0], role: 0 }, { ...members[1], role: 1 }, { memberId: '74', memberName: '队员', role: 0 }];
  h.info({ members: before, leader: false });
  assert.equal(h.count('/api/team/applications'), 0);
  const after = [{ ...members[0], role: 1 }, before[2]];
  board(h, after); h.info({ members: after, leader: true });
  const current = h.request('/api/team/applications'); assert.ok(current, '新队长不能被旧非队长投影锁住');
  current.success({ code: 200, data: [applicant(76)] });
  assert.equal(h.page.data.leader, true); assert.equal(h.page.data.applicantsState, 'ready');
  assert.equal(h.page.data.applicants[0].memberId, 76);
});

function checkLatestApplicants(source) {
  const h = boot(source), old = h.request('/api/team/applications');
  h.page._loadApplicants(h.page._readContext, true);
  const current = h.request('/api/team/applications');
  current.success({ code: 200, data: [applicant(74)] });
  old.success({ code: 200, data: [applicant(73)] }); old.fail({}); current.fail({});
  assert.equal(h.page.data.applicantsState, 'ready'); assert.equal(h.page.data.applicants[0].memberId, 74);
}
test('同一页重复申请读取只接收最新读代，成功后迟失败也不覆写', () => checkLatestApplicants());

for (const change of ['hide', 'unload', 'account', 'token', 'team']) test('申请迟回包拒绝跨' + change, () => {
  const h = boot(), old = h.request('/api/team/applications');
  if (change === 'hide') h.page.onHide();
  if (change === 'unload') h.page.onUnload();
  if (change === 'account') h.account('72', 'isolated-b');
  if (change === 'token') h.account('71', 'isolated-renewed');
  if (change === 'team') h.page.teamId = '99';
  old.success({ code: 200, data: [applicant(73)] }); old.fail({});
  assert.equal(h.page.data.applicants.length, 0);
});

function checkSeatRefresh(source) {
  const h = boot(source); board(h, expanded());
  assert.equal(h.page.data.members.length, 3); assert.equal(h.page.data.team.joinedCount, 3);
  assert.deepEqual(copy(h.page.data.openSeats), []); assert.deepEqual(copy(h.page.data.seatChips), []);
  assert.equal(h.count('/api/team/info'), 2, '只补一次既有info权威读取');
  h.page.openInvite(); assert.equal(h.page.data.inviteCard.seatsLeft, null, '未知席位不能在邀请卡冒充0');
  h.info({ members: expanded(), seats: { capacity: 4, occupied: 3, available: 0, paidOccupied: 4, pendingApplications: 0 } });
  assert.equal(h.page.data.openSeats.length, 0, '不得按4-3猜1个可领取席位');
  h.page.openInvite(); assert.equal(h.page.data.inviteCard.seatsLeft, 0, '权威确认0时仍展示真实0');
  assert.equal(h.page.data.seatChips.find(row => row.key === 'paid').value, '4');
  assert.equal(h.page.data.board.summary.teamDoneCount, 0); assert.equal(h.page.data.board.tasks[0].teamDone, false);
  assert.equal(h.count('/api/orient/team/detail'), 1, 'info-only不再触发board，不产生刷新环');
  assert.equal(h.count('/api/team/info'), 2); return h;
}
test('成员变化失效旧席位并只读一次权威info，保留服务端进度且不刷新环', () => checkSeatRefresh());

test('席位重读失败不恢复旧空位，下一次手动刷新仍可恢复', () => {
  const h = boot(); board(h, expanded()); h.request('/api/team/info').fail({});
  assert.deepEqual(copy(h.page.data.openSeats), []); assert.deepEqual(copy(h.page.data.seatChips), []);
  assert.equal(h.count('/api/team/info'), 2); assert.equal(h.count('/api/orient/team/detail'), 1);
  h.page.load(); h.info({ members: expanded() }); board(h, expanded());
  assert.equal(h.page.data.openSeats.length, 1); assert.equal(h.page.data.boardState, 'ready');
  assert.equal(h.count('/api/team/info'), 3);
});

test('权威重读未给seats时保持未知，不按board成员数猜付费席位', () => {
  const h = boot(); board(h, expanded()); h.info({ members: expanded(), seats: null });
  assert.deepEqual(copy(h.page.data.openSeats), []); assert.deepEqual(copy(h.page.data.seatChips), []);
});

test('旧申请拒绝确认跨招募状态不发新审批，正常队长审批成功路径保留', async () => {
  const h = boot(); h.request('/api/team/applications').success({ code: 200, data: [applicant(73)] });
  await h.page.approveApplicant({ currentTarget: { dataset: { id: 73 } } });
  assert.equal(JSON.parse(h.request('/api/team/command').data).action, 'handle');
  const old = boot(); old.request('/api/team/applications').success({ code: 200, data: [applicant(73)] });
  old.page.rejectApplicant({ currentTarget: { dataset: { id: 73, name: '申请人' } } });
  old.page.load(); old.info({ team: { status: 1 } });
  await old.page.onConfirmReject({ detail: { params: { id: 73 } } });
  assert.equal(old.count('/api/team/command'), 0);
});

test('权威info又变成员时不把新roster贴旧进度，不自动反复读取', () => {
  const h = boot(); board(h, expanded()); h.info();
  assert.equal(h.page.data.members.length, 2); assert.equal(h.page.data.openSeats.length, 2);
  assert.equal(h.page.data.board, null); assert.equal(h.page.data.boardState, 'error');
  assert.equal(h.count('/api/team/info'), 2); assert.equal(h.count('/api/orient/team/detail'), 1);
});

test('未变快照不额外重读info，邀请人始终取当前memberName', () => {
  const h = boot(); h.page.openInvite(); assert.equal(h.page.data.inviteCard.inviter, '当前队长');
  board(h); assert.equal(h.count('/api/team/info'), 1);
  h.page.data.members[0].memberName = '正常重读的新昵称'; h.page.openInvite();
  assert.equal(h.page.data.inviteCard.inviter, '正常重读的新昵称');
  h.page.data.members = []; h.page.openInvite(); assert.equal(h.page.data.inviteCard.inviter, '');
});

function kickVisible(h, status) {
  const tag = fs.readFileSync(WXML, 'utf8').match(/<view\b[^>]*bindtap="kick"[^>]*>/)[0];
  const expression = tag.match(/wx:if="{{([^}]+)}}"/)[1];
  return Function('joined', 'leader', 'item', 'team', 'return !!(' + expression + ')')(
    h.page.data.joined, h.page.data.leader, h.page.data.members[1], { ...h.page.data.team, status });
}
test('kick UI与新确认仅允许0/1当前队长、当前非队长成员', async () => {
  for (const status of [0, 1, 2, 3, 4, -1]) {
    const h = mount(); h.info({ team: { status } });
    const allowed = status === 0 || status === 1;
    assert.equal(kickVisible(h, status), allowed); kick(h); await confirmKick(h);
    assert.equal(h.opened.length, allowed ? 1 : 0);
    assert.equal(h.count('/api/team/command'), allowed ? 1 : 0);
    if (allowed) {
      const request = h.request('/api/team/command'), input = JSON.parse(request.data);
      assert.equal(input.targetId, teamId); assert.equal(input.memberId, '72'); assert.equal(input.action, 'kick');
      request.success(receipt(request, 'APPLIED'));
      assert.equal(h.request('/api/team/info').url, '/api/team/info');
    }
  }
});

async function checkOldKick(source) {
  const h = boot(source); kick(h); assert.equal(h.opened.length, 1);
  h.page.load(); h.info({ team: { status: 3 } }); await confirmKick(h);
  assert.equal(h.count('/api/team/command'), 0, '旧确认跨状态不得发送新kick'); return h;
}
test('旧kick确认跨状态/成员/角色/重读/账号不可写', async () => {
  await checkOldKick();
  for (const change of ['member', 'leader', 'refresh', 'account', 'team']) {
    const h = boot(); kick(h);
    if (change === 'account') h.account('72', 'isolated-b');
    else if (change === 'team') h.page.teamId = '99';
    else { h.page.load(); h.info(change === 'member' ? { members: [members[0]] } : change === 'leader' ? { leader: false } : {}); }
    await confirmKick(h); assert.equal(h.count('/api/team/command'), 0, change);
  }
});

test('已发送kick的UNKNOWN查询/原输入重试不被新动作状态围栏改写', async () => {
  const h = boot(); kick(h); await confirmKick(h);
  const original = h.request('/api/team/command'); original.fail({});
  h.page.load(); h.info({ team: { status: 3 } });
  h.page.queryCommand(); const query = h.request('/api/team/command/query');
  assert.equal(JSON.parse(query.data).requestId, JSON.parse(original.data).requestId); query.fail({});
  h.page.retryCommand(); assert.equal(h.request('/api/team/command').data, original.data);
});

test('负控：移除申请读代/权威重读/昵称字段/kick提交围栏都被实际Page断言检出', async () => {
  const source = fs.readFileSync(FILE, 'utf8');
  const applicants = source.replace('epoch === this._applicantsEpoch && ', '');
  assert.notEqual(applicants, source); assert.throws(() => checkLatestApplicants(applicants), assert.AssertionError);
  const seats = source.replace('if (membershipChanged) this.load(false, true);', 'if (membershipChanged) {}');
  assert.notEqual(seats, source); assert.throws(() => checkSeatRefresh(seats), assert.AssertionError);
  const nickname = source.replace("String(lead.memberName || '').trim()", "String(lead.nickname || '').trim()");
  assert.notEqual(nickname, source); const h = boot(nickname); h.page.openInvite();
  assert.throws(() => assert.equal(h.page.data.inviteCard.inviter, '当前队长'), assert.AssertionError);
  const kick = source.replace(/    if \(action === 'kick' &&[\s\S]*? return;\n/, '');
  assert.notEqual(kick, source); await assert.rejects(() => checkOldKick(kick), assert.AssertionError);
});
