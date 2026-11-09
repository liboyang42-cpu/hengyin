'use strict';
// 第十轮 f1 item 6(j6 F1):owner 2026-10-24(app.js:142-144)—— 游客的账号只在写入动作的登录弹窗里创建。
// 本文件钉住「没有页面替游客自己登录」:
//   ① 商家页会话助手对游客一律回 false、不碰 wx.login / 会话管理器;只有 401 续期(renew=true)才续老账号;
//   ② 商家页里 renew=true 只出现在 recover* 续期方法里,不带 renew 的调用前必须有 isGuest 早退;
//   ③ 页面/组件里能触发登录的调用(firstLogin / reLogin / getSessionManager / wx.login)只在下列已审文件里:
//      它们是用户点「登录」按钮、已登录会员的续期或微信运动取 code。新增调用点必须先审再加进来。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');
const helper = require('../../pages/merchant/utils/session/ensure-session.js');

function fakeApp(member) {
  const calls = { manager: 0 };
  return {
    calls,
    getUserID: () => member,
    getSessionManager: () => ({ ensureSession: () => { calls.manager += 1; return Promise.resolve({ ok: true }); } }),
  };
}

test('会话助手:游客回 false 且不走登录;会员续缓存会话;renew 只给 401 续期', async () => {
  for (const guest of ['', 0, '0', null]) {
    const app = fakeApp(guest);
    assert.equal(await helper.ensureSession(app), false);
    assert.equal(app.calls.manager, 0, `游客(${JSON.stringify(guest)})不得走 wx.login → /api/login/code`);
    assert.equal(helper.isGuest(app), true);
  }
  const member = fakeApp('9');
  assert.equal(await helper.ensureSession(member), true);
  assert.equal(member.calls.manager, 1);
  assert.equal(helper.isGuest(member), false);
  const renew = fakeApp('');
  assert.equal(await helper.ensureSession(renew, true), true);
  assert.equal(renew.calls.manager, 1);
});

function walk(dir, out) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (['node_modules', 'miniprogram_npm', 'tests', 'scripts', 'docs'].includes(entry.name) || entry.name.startsWith('.')) continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full, out);
    else if (entry.name.endsWith('.js')) out.push(full);
  }
  return out;
}
const rel = (file) => path.relative(ROOT, file).split(path.sep).join('/');

function enclosingMethod(source, index) {
  const head = source.slice(0, index);
  const re = /\n {2}(?:async\s+)?([A-Za-z_$][\w$]*)\s*\([^)]*\)\s*\{/g;
  let match; let name = '';
  while ((match = re.exec(head))) name = match[1];
  return { name, body: head.slice(head.lastIndexOf('\n  ' + name)) };
}

test('商家页:renew 只在 recover* 续期里;不带 renew 的调用前有 isGuest 早退', () => {
  const files = walk(path.join(ROOT, 'pages/merchant'), []);
  let seen = 0;
  for (const file of files) {
    const source = fs.readFileSync(file, 'utf8');
    const re = /\bensureSession\((app)(, true)?\)/g;
    let match;
    while ((match = re.exec(source))) {
      seen += 1;
      const { name, body } = enclosingMethod(source, match.index);
      if (match[2]) {
        assert.match(name, /^recover/, `${rel(file)} 的 ${name} 用 renew 续期,只允许 401 续期方法`);
      } else {
        assert.match(body, /if \(isGuest\(app\)\)[^\n]*return;/, `${rel(file)} 的 ${name} 对游客没有早退`);
      }
    }
  }
  assert.ok(seen >= 6, '扫描锚点失效:商家页里应有 ensureSession 调用');
});

const LOGIN_CALL = /\bfirstLogin\s*\(|\breLogin\s*\(|getSessionManager\s*\(|\bwx\.login\s*\(/;
const REVIEWED = new Set([
  'app.js', // 冷启动只恢复缓存会话;firstLogin / reLogin 本体
  'utils/auth/require-login.js', // 写前登录弹窗:账号只在这里创建
  'utils/session/login-reload.js', // 「登录后查看 · 登录」按钮的 retryLogin
  'utils/session/session-manager.js',
  'utils/transport/request-client.js', // 只给发起时已有会员的 401 续期
  'utils/deregister-flow.js', // 注销流程:已登录会员
  'components/cy/access-gate/index.js', // onLogin 按钮
  'pages/index/index.js', // 已登录会员 user/info 401 续期
  'pages/play/index.js', // 已登录会员续期;微信运动取 code
  'pages/play/utils/theme-backpack.js', // 微信运动取 code
  'pages/privacy/index.js', // onMediaLogin 按钮
  'pages/roam/index.js', // requireLogin 写前登录(开局)
  'pages/shezhi/shezhi.js', // 「点击登录」按钮
  'pages/talent/list/index.js', // 「登录」重试按钮
  'pages/merchant/utils/session/ensure-session.js', // 本文件 ① 钉住游客回 false
  'subpackageA/components/scene-asset-earnings/index.js', // 「登录」重试按钮
  'subpackageMember/complaint/index.js', // loginToRead 按钮
  'subpackageMember/components/cy/scene-member-order-history/index.js', // 「登录」重试按钮
  'subpackageP3/pages/badge-wall/index/index.js', // 「登录」重试按钮
  'subpackageP3/pages/growthcenter/index/index.js', // 「登录」重试按钮
  'subpackageP3/pages/growthcenter/leaderboard/index.js', // 「登录」重试按钮
]);

test('能触发登录的调用只在已审文件里;j6 F1 的商家入口一个都不在', () => {
  const hits = walk(ROOT, []).filter((file) => LOGIN_CALL.test(fs.readFileSync(file, 'utf8'))).map(rel);
  const unreviewed = hits.filter((file) => !REVIEWED.has(file));
  assert.deepEqual(unreviewed, [], '新增登录调用点必须先确认不会替游客静默建号,再登记进 REVIEWED');
  for (const entry of ['pages/merchant/index/index.js', 'pages/merchant/relation/index.js', 'pages/merchant/game-node/index.js',
    'pages/merchant/verify-records/index.js', 'pages/merchant/crm-campaigns/index.js', 'pages/merchant/team/index.js',
    'subpackageMember/tixian/tixian.js', 'pages/merchant/marketing/index.js']) {
    assert.equal(hits.includes(entry), false, `${entry} 不得自己调登录`);
  }
});
