'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const file = path.resolve(__dirname, '../../pages/team/detail/index.js');
const { platform, receipt } = require('./helpers/team-event-platform');
const copy = v => JSON.parse(JSON.stringify(v));
function boot() {
  let definition, user = '71', token = 'a';
  const requests = [], effects = [];
  const app = { globalData: {}, getUserID: () => user, getAuthorization: () => token,
    getRequestErrorMessage: (r, f) => r && r.msg || f, sendRequest: r => requests.push(r) };
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
    Page: d => { definition = d; }, getApp: () => app, wx: platform(),
    require: id => id.includes('toast') ? m => effects.push(m) : createRequire(file)(id)
  });
  const dc = { close() {}, busyOn: () => effects.push('busy'), done: () => effects.push('done'), failed: () => effects.push('failed') };
  const page = { ...definition, data: copy(definition.data), selectComponent: () => dc,
    setData(patch) { for (const [key, val] of Object.entries(patch)) {
      const parts = key.split('.'); if (parts.length === 2) this.data[parts[0]][parts[1]] = val; else this.data[key] = val;
    } } };
  page.onLoad({ teamId: '9' }); page.onShow();
  const info = { team: { id: 9, ownerId: 10, ownerType: 1, joinMode: 2, status: 0, inviteCode: 'PRIVATE', leaderMemberId: 71 },
    members: [{ memberId: 71, role: 1 }, { memberId: 72, role: 0 }], joined: true, leader: true };
  requests[0].success({ code: 200, data: copy(info) });
  return { page, requests, effects, info, account() { user = '72'; token = 'b'; } };
}
test('quit success invalidates in-flight info and reloads, old response cannot restore membership', async () => {
  const h = boot(); h.page.load(); const old = h.requests.at(-1);
  await h.page.onConfirmQuit(); const quit = h.requests.at(-1);
  quit.success(receipt(quit, 'APPLIED'));
  assert.equal(h.requests.at(-1).url, '/api/team/info'); assert.notEqual(h.requests.at(-1), old);
  assert.equal(h.page.data.joined, false); assert.equal(h.page.data.board, null);
  old.success({ code: 200, data: h.info });
  assert.equal(h.page.data.joined, false); assert.equal(h.page.data.leader, false);
});
test('kick success invalidates old board and starts fresh roster read', async () => {
  const h = boot(); const board = h.requests.at(-1);
  await h.page.onConfirmKick({ detail: { params: { id: 72 } } });
  h.requests.at(-1).success(receipt(h.requests.at(-1), 'APPLIED'));
  assert.equal(h.requests.at(-1).url, '/api/team/info');
  board.fail({ code: 500, errorCode: 'ORIENT_TEAM_NOT_MEMBER' });
  assert.notEqual(h.page.data.boardState, 'not-member');
});
test('team writes are single flight across quit and disband', async () => {
  const h = boot(); await h.page.onConfirmQuit(); h.page.onConfirmDisband();
  assert.equal(h.requests.filter(r => r.url === '/api/team/command').length, 1);
});
test('write response after switch does not show old receipt or refresh another account', async () => {
  const h = boot(); await h.page.onConfirmQuit(); const req = h.requests.at(-1), count = h.requests.length;
  h.account(); req.success(receipt(req, 'APPLIED'));
  assert.equal(h.requests.length, count); assert.ok(!h.effects.includes('done'));
  h.page.onShow(); assert.equal(h.page.data.joined, false);
});
test('membership denial clears member-only controls and roster, no stale invitation', () => {
  const h = boot(); h.requests.at(-1).success({ code: 500, errorCode: 'ORIENT_TEAM_NOT_MEMBER' });
  assert.equal(h.page.data.joined, false); assert.equal(h.page.data.leader, false);
  assert.deepEqual(copy(h.page.data.members), []); assert.ok(!h.page.data.team.inviteCode);
});
test('explicit info authorization failure clears cached team', () => {
  const h = boot(); h.page.load(); h.requests.at(-1).success({ code: 403, msg: 'forbidden' });
  assert.equal(h.page.data.team, null); assert.equal(h.page.data.joined, false);
  assert.deepEqual(copy(h.page.data.members), []);
});
test('join mode response from old account cannot replace new team mode', async () => {
  const h = boot(); await h.page.onJoinModeChange({ detail: { value: false } });
  const req = h.requests.at(-1); h.account(); req.success(receipt(req, 'APPLIED'));
  assert.ok(!h.effects.includes('已改为仅邀请')); h.page.onShow(); assert.equal(h.page.data.leader, false);
});

test('board snapshot updates visible roster and role together with progress', () => {
  const h = boot();
  h.requests.at(-1).success({ code: 200, data: {
    team: { teamId: 9, ownerId: 10, ownerType: 1 },
    threshold: { activeCount: 1, required: 1, ratio: 1 },
    summary: { teamDoneCount: 0, totalTasks: 0 }, tasks: [],
    members: [{ memberId: 71, name: 'current', role: 0, doneTaskIds: [] }]
  } });
  assert.deepEqual(copy(h.page.data.members.map(m => m.memberId)), ['71']);
  assert.equal(h.page.data.team.joinedCount, 1); assert.equal(h.page.data.leader, false);
  assert.equal(h.page.data.board.threshold.activeCount, 1);
});
