// qx-b4 U06-01:席位/邀请/阻塞码只翻译 B6 投影;已撤回字段不读;未知不写 0。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const s = require('../../pages/team/utils/team-seats.js');

test('席位行:票已失效、待审申请只在 >0 时出现;缺字段整行不画', () => {
  const rows = s.seatRows({ capacity: 4, occupied: 2, available: 2, paidOccupied: 0, paidTicketMissing: 2, pendingApplications: 1 });
  assert.deepEqual(rows.map(r => [r.label, r.value]), [['持有效票', '0'], ['票已失效', '2'], ['待审申请', '1'], ['还可加入', '2']]);
  assert.equal(rows.find(r => r.key === 'missing').bad, true);
  assert.deepEqual(s.seatRows({ paidTicketMissing: 0, pendingApplications: 0 }), []);
  assert.deepEqual(s.seatRows(null), []);
  // 已撤回字段即使出现也不读
  assert.deepEqual(s.seatRows({ claimable: 3, reservedApplications: 1, unpaidOccupied: 2 }), []);
});

test('邀请期限只回放 expiresAt;失效以服务端为准', () => {
  assert.match(s.inviteText({ available: true, expired: false, expiresAt: '2026-11-12T21:01:53.000+08:00' }), /^11月12日 \d\d:\d\d 到期$/);
  assert.equal(s.inviteText({ available: false, expired: true, expiresAt: '2026-11-12T21:01:53.000+08:00' }), '邀请已失效');
  assert.equal(s.inviteText({ available: true, expired: false }), '');
  assert.equal(s.inviteText(null), '');
});

test('阻塞码按服务端码分支,不解析中文', () => {
  assert.deepEqual(s.blockerText({ joinBlockers: ['TICKET_REQUIRED'] }, 'join'), { code: 'TICKET_REQUIRED', text: '需要先买这一场的票' });
  assert.deepEqual(s.blockerText({ joinBlockers: ['ALREADY_JOINED'] }, 'join'), { code: '', text: '' });
  assert.equal(s.blockerText({ applyBlockers: ['TEAM_FULL'] }, 'apply').text, '队伍已满');
  assert.equal(s.blockerText(undefined, 'join').text, '');
});

test('加入按钮仍由 /join 回包裁决:阻塞提示不禁用按钮', () => {
  const wxml = fs.readFileSync(path.join(__dirname, '../../pages/team/join/index.wxml'), 'utf8');
  assert.match(wxml, /joinHint && !joined/);
  assert.doesNotMatch(wxml, /joinHint[^}]*disabled/);
});
