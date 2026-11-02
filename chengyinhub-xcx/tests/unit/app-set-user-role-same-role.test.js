'use strict';
// 2026-11-01 VPS master 测试服实测:玩家首页 / 商家首页加载时把后端回来的同一个角色再写一遍,
// 会话版本被推进,同屏还在路上的请求全部作废成「登录状态已变更」—— 玩家首页「编辑精选 / 附近活动 /
// 即将上线」只剩空标题,商家首页「收入没更新成功」。
// 契约:角色没变不推进会话版本(能力快照照常刷新);角色真变了照常推进(见 registration-seat-session-revision)。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createSessionStore } = require('../../utils/session/session-store.js');

const source = fs.readFileSync(path.resolve(__dirname, '../../app.js'), 'utf8');
const start = source.indexOf('  setUserRole: function (snapOrRole) {');
const end = source.indexOf('\n  },', start);
assert.ok(start > 0 && end > start);

function fixture(role) {
  const rows = {};
  const wx = { getStorageSync: (key) => rows[key], setStorageSync(key, value) { rows[key] = JSON.parse(JSON.stringify(value)); },
    removeStorageSync(key) { delete rows[key]; } };
  const session = createSessionStore({ wx, target: {}, now: () => 1000 });
  session.applySnapshot({ user_id: 11, authorization: 'synthetic-authorization', role });
  // 执行真实的 app.setUserRole 与真实 session-store,不伪造版本号。
  const app = Object.assign(new Function('wx', 'return ({' + source.slice(start + 2, end + 4) + '});')(wx), { getSession: () => session });
  return { rows, session, app };
}

test('同一角色(字符串)回写不推进会话版本', () => {
  const h = fixture('merchant'), revision = h.session.getRevision();
  h.app.setUserRole('merchant');
  assert.equal(h.session.getRevision(), revision);
});

test('同一角色的能力快照只刷新能力缓存,不推进会话版本', () => {
  const h = fixture('player'), revision = h.session.getRevision();
  h.app.setUserRole({ role: 'player', isClubLeader: true });
  assert.equal(h.session.getRevision(), revision);
  assert.equal(h.rows.role_permission_cache_v1.isClubLeader, true);
});

test('角色真变了照常回写并推进会话版本', () => {
  const h = fixture('player'), revision = h.session.getRevision();
  h.app.setUserRole({ role: 'club' });
  assert.equal(h.session.getUserRole(), 'club');
  assert.ok(h.session.getRevision() > revision);
  h.app.setUserRole('');
  assert.equal(h.session.getUserRole(), 'club');
});
