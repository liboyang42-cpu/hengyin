'use strict';
// qx-sm：显式状态机工具与协作邀约状态机；附「不得再手写同一张状态表」lint。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const { createStatusMachine, normalizeCode } = require('../../utils/state-machine.js');
const { COOP_INVITE_STATUS } = require('../../utils/coop-invite-status.js');

const root = path.join(__dirname, '..', '..');

test('normalizeCode 只接受整数与纯数字串', () => {
  assert.equal(normalizeCode(2), 2);
  assert.equal(normalizeCode(' 3 '), 3);
  for (const bad of [null, undefined, '', 'x', 1.5, NaN, {}, true]) assert.equal(normalizeCode(bad), null);
});

test('createStatusMachine 拒绝重复态与未登记迁移端点', () => {
  assert.throws(() => createStatusMachine({ name: 'a', states: [] }));
  assert.throws(() => createStatusMachine({ name: 'a', states: [{ code: 0, key: 'x', label: '' }, { code: 0, key: 'y', label: '' }] }));
  assert.throws(() => createStatusMachine({ name: 'a', states: [{ code: 0, key: 'x', label: '' }], transitions: { x: ['nope'] } }));
  assert.throws(() => createStatusMachine({ name: 'a', states: [{ code: 0, key: 'x', label: '' }], transitions: { nope: ['x'] } }));
});

test('协作邀约：只有待确认可迁移，其余为终态；未知码落 unknown', () => {
  assert.deepEqual(COOP_INVITE_STATUS.targets(0), ['accepted', 'rejected', 'cancelled', 'replaced', 'expired']);
  for (const code of [1, 2, 3, 4, 5]) {
    assert.equal(COOP_INVITE_STATUS.isTerminal(code), true, String(code));
    for (const to of [0, 1, 2, 3, 4, 5]) assert.equal(COOP_INVITE_STATUS.can(code, to), false, code + '->' + to);
  }
  assert.equal(COOP_INVITE_STATUS.can(0, 0), false);
  assert.equal(COOP_INVITE_STATUS.can('0', '1'), true);
  assert.equal(COOP_INVITE_STATUS.can(0, 9), false);
  assert.equal(COOP_INVITE_STATUS.keyOf(9), 'unknown');
  assert.equal(COOP_INVITE_STATUS.isTerminal(9), false);
  assert.equal(COOP_INVITE_STATUS.label(9), '');
  assert.equal(COOP_INVITE_STATUS.label(null, '合作动态'), '合作动态');
});

test('俱乐部详情的收到邀约文案与协作列表同源', () => {
  const view = require('../../utils/coop-invite-view.js');
  assert.deepEqual(Array.from(view.STATUS_LABEL), Array.from(COOP_INVITE_STATUS.toArray('label')));
  const js = fs.readFileSync(path.join(root, 'pages/club/detail/index.js'), 'utf8');
  assert.match(js, /const RECEIVED_STATUS_TEXT = STATUS_LABEL;/);
});

test('lint：协作邀约状态表不得在状态机以外手写', () => {
  const literal = /'待确认',\s*'已接受',\s*'已拒绝',\s*'已取消',\s*'已顶替'/;
  const offenders = [];
  const skip = new Set(['node_modules', 'tests', 'scripts', 'miniprogram_npm', '.git']);
  (function walk(dir) {
    for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
      if (skip.has(ent.name)) continue;
      const p = path.join(dir, ent.name);
      if (ent.isDirectory()) walk(p);
      else if (ent.name.endsWith('.js') && literal.test(fs.readFileSync(p, 'utf8'))) offenders.push(path.relative(root, p));
    }
  })(root);
  assert.deepEqual(offenders, [], '请改用 utils/coop-invite-status.js');
});
