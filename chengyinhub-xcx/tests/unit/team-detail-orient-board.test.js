const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const file = path.resolve(__dirname, '../../pages/team/detail/index.js');
const { platform, receipt } = require('./helpers/team-event-platform');
const clone = v => JSON.parse(JSON.stringify(v));
const teamId = '9007199254740993';
const ownerId = '9223372036854775807';
function mount() {
  let definition, user = '71', token = 'token-a';
  const requests = [], writes = [];
  const app = { globalData: {}, getUserID: () => user, getAuthorization: () => token,
    getRequestErrorMessage: (res, fallback) => res && res.msg || fallback,
    sendRequest: r => { requests.push(r); return { abort() {} }; } };
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), { Page: d => { definition = d; }, getApp: () => app,
    wx: platform(), console,
    require: id => id.includes('toast') ? () => {} : createRequire(file)(id) });
  const page = { ...definition, data: clone(definition.data),
    setData(patch) { writes.push(clone(patch)); for (const [key, value] of Object.entries(patch)) {
      const parts = key.split('.'); if (parts.length === 2) this.data[parts[0]][parts[1]] = value; else this.data[key] = value;
    } } };
  page.onLoad({ teamId });
  return { page, requests, writes, account(id, auth) { user = id; token = auth; },
    info(extra = {}) {
      requests.at(-1).success({ code: 200, data: { team: { id: page.teamId, ownerType: 2, ownerId, status: 0, joinMode: 2 },
        members: [], joined: true, leader: true, ...extra } });
    } };
}
function detail(id = teamId) {
  return { team: { teamId: id, ownerType: 2, ownerId }, threshold: { ratio: 0.6, activeCount: 2, required: 2 },
    summary: { teamDoneCount: 0, totalTasks: 1 },
    tasks: [{ taskId: '9007199254740995', name: '观察街角', completedCount: 2, teamDone: false }],
    members: [{ memberId: '71', name: '甲', doneTaskIds: ['9007199254740995'] }, { memberId: '72', name: '乙', doneTaskIds: [] }] };
}
function start() { const h = mount(); h.page.onShow(); h.info(); return h; }
function ready() { const h = start(); h.requests.at(-1).success({ code: 200, data: detail() }); return h; }

test('real Page consumes exact IDs, server threshold/completion and ordered ranks without local win inference', () => {
  const h = start();
  assert.equal(JSON.parse(h.requests[0].data).teamId, teamId);
  assert.equal(h.requests[1].url, '/api/orient/team/detail');
  assert.equal(h.requests[1].method, 'GET'); assert.equal(h.requests[1].data.teamId, teamId);
  h.requests[1].success({ code: 200, data: detail() });
  assert.equal(h.page.data.board.threshold.required, 2);
  assert.equal(h.page.data.board.tasks[0].teamDone, false, 'completion count cannot override server result');
  assert.deepEqual(clone(h.page.data.board.tasks[0].members.map(m => m.done)), [true, false]);
  // 原型 B T-32:矩阵按成员成行,每格与 tasks[].members[].done 同一份服务端事实
  assert.deepEqual(clone(h.page.data.board.rows.map(r => [r.name, r.cells.map(c => c.done)])), [['甲', [true]], ['乙', [false]]]);
  assert.equal(h.requests[2].url, '/api/orient/team/ranking');
  assert.deepEqual(clone(h.requests[2].data), { ownerType: 2, ownerId });
  h.requests[2].success({ code: 200, data: [
    { teamId: '93', rank: 7, title: '先返回', teamDoneCount: 0, totalTasks: 1 },
    { teamId, rank: 2, title: '后返回', teamDoneCount: 1, totalTasks: 1, mine: true }
  ] });
  assert.deepEqual(clone(h.page.data.boardRanks.map(r => r.rank)), [7, 2]);
  assert.equal(h.page.data.boardRanks[1].teamId, teamId);
  const template = fs.readFileSync(file.replace('.js', '.wxml'), 'utf8');
  for (const field of ['board.summary.teamDoneCount', 'board.threshold.required', 'task.teamDone', 'cell.done', 'item.rank']) assert.ok(template.includes(field));
  assert.match(template, /bind:action="retryBoard"/);
});

for (const [code, state] of [['ORIENT_DISABLED', 'disabled'], ['ORIENT_NOT_ORIENTEERING', 'not-orient'],
  ['ORIENT_TEAM_NOT_MEMBER', 'not-member'], ['ORIENT_LOGIN_REQUIRED', 'login']]) {
  test(code + ' is explicit and never becomes an empty successful board', () => {
    const h = start(); h.requests.at(-1).success({ code: 500, errorCode: code });
    assert.equal(h.page.data.boardState, state); assert.equal(h.page.data.board, null);
    assert.equal(h.requests.length, 2); assert.equal(h.page.data.team.id, teamId);
    const ranked = ready(); ranked.requests.at(-1).fail({ code: 500, errorCode: code });
    assert.equal(ranked.page.data.boardState, state); assert.equal(ranked.page.data.board, null);
  });
}

test('network errors, malformed data and empty ranks remain distinct; retry rereads membership', () => {
  const h = start(); h.requests.at(-1).fail({ errMsg: 'network' });
  assert.equal(h.page.data.boardState, 'error'); assert.equal(h.page.data.board, null);
  h.page.retryBoard(); assert.equal(h.requests.at(-1).url, '/api/team/info');
  h.info(); h.requests.at(-1).success({ code: 200, data: {} }); assert.equal(h.page.data.boardState, 'error');
  const ranked = ready(); ranked.requests.at(-1).fail({ errMsg: 'network' });
  assert.equal(ranked.page.data.rankingState, 'error'); assert.ok(ranked.page.data.board);
  const empty = ready(); empty.requests.at(-1).success({ code: 200, data: [] });
  assert.equal(empty.page.data.rankingState, 'ready'); assert.equal(empty.page.data.boardRanks.length, 0);
});

for (const phase of ['info', 'detail', 'ranking']) for (const change of ['hide', 'unload', 'account', 'token', 'team']) {
  test(phase + ' rejects stale callbacks after ' + change, () => {
    const h = mount(); h.page.onShow();
    if (phase !== 'info') h.info();
    if (phase === 'ranking') h.requests.at(-1).success({ code: 200, data: detail() });
    const old = h.requests.at(-1);
    if (change === 'hide') { h.page.onHide(); h.page.onShow(); }
    if (change === 'unload') h.page.onUnload();
    if (change === 'account') { h.account('72', 'token-b'); h.page.load(); h.account('71', 'token-a'); h.page.load(); }
    if (change === 'token') { h.account('71', 'token-renewed'); h.page.load(); }
    if (change === 'team') { h.page.teamId = '99'; h.page.load(); }
    const before = h.writes.length;
    old.success({ code: 200, data: phase === 'info' ? { team: { id: teamId }, joined: true } : phase === 'detail' ? detail() : [] });
    old.fail({ code: 401 });
    assert.equal(h.writes.length, before); assert.equal(h.page.data.board, null);
  });
}

test('identity change without a reload clears data when pending read returns', () => {
  const h = ready(); h.account('72', 'new-token'); h.requests.at(-1).success({ code: 200, data: [] });
  assert.equal(h.page.data.board, null); assert.equal(h.page.data.team, null); assert.equal(h.page.data.joined, false);
});

test('duplicate callbacks cannot settle next read; member loss clears board; ordinary actions remain usable', async () => {
  const h = ready(); const oldRank = h.requests.at(-1);
  h.page.load(); h.page.load(); assert.equal(h.requests.length, 4, 'info reads are single flight');
  h.info({ joined: false, leader: false });
  oldRank.success({ code: 200, data: [] });
  assert.equal(h.page.data.boardState, 'not-member'); assert.equal(h.page.data.board, null);
  assert.equal(h.requests.length, 4);
  const ordinary = start(); ordinary.requests.at(-1).success({ code: 500, errorCode: 'ORIENT_NOT_ORIENTEERING' });
  await ordinary.page.onJoinModeChange({ detail: { value: false } });
  assert.equal(ordinary.requests.at(-1).url, '/api/team/command');
  assert.equal(JSON.parse(ordinary.requests.at(-1).data).targetId, teamId, 'write keeps Long exact too');
  ordinary.requests.at(-1).success(receipt(ordinary.requests.at(-1), 'APPLIED'));
  ordinary.info({ team: { id: teamId, ownerType: 2, ownerId, status: 0, joinMode: 1 } });
  assert.equal(ordinary.page.data.joinModePublic, false);
});

test('unsafe, missing and cross-team identifiers never start a board request', () => {
  for (const id of ['01', '1e3', '9223372036854775808', 9007199254740992]) {
    const h = mount(); h.page.onLoad({ teamId: id }); h.page.onShow(); assert.equal(h.requests.length, 0);
  }
  for (const owner of [null, 9007199254740992]) {
    const h = mount(); h.page.onShow(); h.info({ team: { id: teamId, ownerType: 2, ownerId: owner } });
    assert.equal(h.requests.length, 1); assert.equal(h.page.data.boardState, 'error');
  }
  const h = start(); h.requests.at(-1).success({ code: 200, data: detail('99') });
  assert.equal(h.page.data.boardState, 'error'); assert.equal(h.requests.length, 2);
  const bad = ready(); bad.requests.at(-1).success({ code: 200, data: [{ teamId: 9007199254740992, rank: 1, teamDoneCount: 0, totalTasks: 0 }] });
  assert.equal(bad.page.data.rankingState, 'error');
});

test('refreshing a previously ready board never retains progress when team info fails', () => {
  const h = ready(); h.page.load(); h.requests.at(-1).fail({ errMsg: 'network' });
  assert.equal(h.page.data.board, null); assert.equal(h.page.data.boardState, 'error');
  assert.ok(h.page.data.team, 'ordinary team snapshot stays available');
  h.page.retryBoard(); h.requests.at(-1).success({ code: 403 });
  assert.equal(h.page.data.board, null); assert.equal(h.page.data.boardState, 'error');
});

test('settled detail and ranking callbacks do not overwrite their first result', () => {
  const h = start(); const request = h.requests.at(-1);
  request.success({ code: 200, data: detail() });
  const rank = h.requests.at(-1); const before = h.writes.length;
  request.fail({ errorCode: 'ORIENT_TEAM_NOT_MEMBER' });
  request.success({ code: 200, data: detail('99') });
  assert.equal(h.writes.length, before);
  rank.success({ code: 200, data: [] }); const settled = h.writes.length;
  rank.fail({ errorCode: 'ORIENT_DISABLED' }); assert.equal(h.writes.length, settled);
});

test('read refresh preserves an in-flight join-mode lock and accepts its eventual write receipt', async () => {
  const h = ready();
  await h.page.onJoinModeChange({ detail: { value: false } }); const write = h.requests.at(-1);
  assert.equal(write.url, '/api/team/command');
  assert.equal(JSON.parse(write.data).action, 'join-mode');
  h.page.load(); h.info();
  assert.equal(h.page.data.joinModeSaving, true);
  const before = h.requests.length;
  h.page.onJoinModeChange({ detail: { value: false } });
  assert.equal(h.requests.length, before, 'read refresh must not allow another write');
  write.success(receipt(write, 'APPLIED'));
  h.info({ team: { id: teamId, ownerType: 2, ownerId, status: 0, joinMode: 1 } });
  assert.equal(h.page.data.joinModeSaving, false);
  assert.equal(h.page.data.team.joinMode, 1);
});

test('switch off, ordinary team and non-member hide the whole board section without a notice', () => {
  const template = fs.readFileSync(file.replace('.js', '.wxml'), 'utf8');
  const conditions = [...template.matchAll(/wx:if="\{\{([^}]*boardState[^}]*)\}\}"/g)].map(m => m[1]);
  assert.ok(conditions.length >= 3);
  const visible = data => conditions.filter(expr => new Function('boardState', 'board', 'return (' + expr + ')')(data.boardState, data.board));
  for (const code of ['ORIENT_DISABLED', 'ORIENT_NOT_ORIENTEERING', 'ORIENT_TEAM_NOT_MEMBER']) {
    const h = start(); h.requests.at(-1).success({ code: 500, errorCode: code });
    assert.deepEqual(visible(h.page.data), [], code + ' must not render a title or notice');
    assert.equal(h.page.data.boardMessage, '');
  }
  const outsider = mount(); outsider.page.onShow(); outsider.info({ joined: false, leader: false });
  assert.equal(outsider.requests.length, 1); assert.deepEqual(visible(outsider.page.data), []);
  assert.ok(visible(ready().page.data).length >= 2, 'applicable board keeps its title and content');
  assert.ok(visible({ boardState: 'error', board: null }).length >= 2, 'board errors stay explicit');
});
