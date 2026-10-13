'use strict';

// Q119/Q120 controlled fixture driver. It deliberately does not create a login,
// inject identity/data, or claim device/visual evidence. Run only after a screenshot slot is granted.

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { openMiniProgram } = require('../tests/automator/harness');

const OUTPUT_DIR = process.env.Q119_Q120_SHOT_DIR || '/tmp/q119-q120-screenshot-fixture-0803';
const PORT = Number(process.env.WX_AUTO_PORT);
const PROJECT_PATH = path.resolve(__dirname, '..');
const EVIDENCE_KIND = 'controlled-fixture-not-real-data-or-device';
const STABLE_WAIT_MS = 450;
const OVERLAY_SELECTORS = ['.wx-toast', '.wx-modal', '.cy-toast', '.cy-modal', '.cy-sheet-mask'];

const FIXTURES = [
  {
    file: '01-normal.png', state: 'normal', route: '/pages/club/apply/index', pagePath: 'pages/club/apply/index',
    visible: { selector: '.h1', text: '主理人实名' }, exclusive: { selector: '.dots' },
    apply: (page) => page.setData({ mode: 'form', step: 1, stepTitle: '主理人实名', canNext: false, submitting: false })
  },
  {
    file: '02-error.png', state: 'error', route: '/subpackageMember/coupon-qr/index?id=1', pagePath: 'subpackageMember/coupon-qr/index',
    visible: { selector: 'cy-qr-voucher' }, exclusive: { selector: 'cy-qr-voucher', dataPath: 'state', expected: 'error' },
    apply: (page) => page.setData({ qrState: 'error', errMsg: '当前受控状态不可用', qrcodeUrl: '', useStatus: 0, countdown: 60, coupon: {} })
  },
  {
    file: '03-empty.png', state: 'empty', route: '/pages/coop/nearby/index', pagePath: 'pages/coop/nearby/index',
    visible: { selector: 'cy-empty' }, exclusive: { selector: 'cy-empty' },
    apply: (page) => page.setData({ pageState: 'ready', merchants: [] })
  },
  {
    file: '04-loading.png', state: 'loading', route: '/pages/coop/nearby/index', pagePath: 'pages/coop/nearby/index',
    visible: { selector: 'cy-skeleton' }, exclusive: { selector: 'cy-skeleton' },
    apply: (page) => page.setData({ pageState: 'loading', merchants: [] })
  }
];

const wait = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));
const assert = (condition, message) => { if (!condition) throw new Error(message); };
const md5 = (file) => crypto.createHash('md5').update(fs.readFileSync(file)).digest('hex');

function validateFixturePlan(fixtures) {
  const states = ['normal', 'error', 'empty', 'loading'];
  assert(Array.isArray(fixtures) && fixtures.length === states.length, 'fixture 必须正好覆盖 normal/error/empty/loading');
  assert(fixtures.map((fixture) => fixture.state).join(',') === states.join(','), 'fixture 状态顺序必须是 normal/error/empty/loading');
  for (const fixture of fixtures) {
    assert(typeof fixture.route === 'string' && fixture.route.startsWith('/'), `${fixture.state} route 非法`);
    assert(fixture.pagePath === fixture.route.slice(1).split('?')[0], `${fixture.state} pagePath 必须由 route 推导`);
    assert(fixture.visible && fixture.visible.selector && fixture.exclusive && fixture.exclusive.selector, `${fixture.state} 缺少首屏独有节点断言`);
    assert(typeof fixture.apply === 'function', `${fixture.state} 缺少受控状态切换`);
  }
  const exclusive = new Set();
  for (const fixture of fixtures) {
    const key = `${fixture.exclusive.selector}:${fixture.exclusive.dataPath || ''}:${fixture.exclusive.expected || ''}`;
    assert(!exclusive.has(key), `${fixture.state} 态专属断言重复`);
    exclusive.add(key);
  }
  return true;
}

function validateSessionSnapshot(before, after) {
  const keys = ['hasAuthorization', 'hasUserId', 'hasRole', 'hasUserInfo', 'interceptedRequests'];
  for (const snapshot of [before, after]) {
    assert(snapshot && Object.keys(snapshot).every((key) => keys.includes(key)), 'session 快照只能含布尔/计数元数据');
    assert(typeof snapshot.hasAuthorization === 'boolean' && typeof snapshot.hasUserId === 'boolean' && typeof snapshot.hasRole === 'boolean' && typeof snapshot.hasUserInfo === 'boolean', 'session 快照必须只暴露布尔状态');
    assert(Number.isInteger(snapshot.interceptedRequests || 0) && (snapshot.interceptedRequests || 0) >= 0, 'session 拦截计数非法');
  }
  assert(before.hasAuthorization === after.hasAuthorization && before.hasUserId === after.hasUserId && before.hasRole === after.hasRole && before.hasUserInfo === after.hasUserInfo, 'session 在隔离后发生变化，拒绝截图假绿');
  return true;
}

function validateManifest(entries) {
  validateFixturePlan(FIXTURES);
  assert(Array.isArray(entries) && entries.length === FIXTURES.length, 'manifest 必须正好包含四帧');
  const states = new Set();
  const hashes = new Set();
  for (const entry of entries) {
    assert(entry && typeof entry.route === 'string' && entry.route.startsWith('/'), 'manifest route 非法');
    assert(typeof entry.file === 'string' && entry.file.endsWith('.png'), 'manifest file 非法');
    assert(/^[a-f0-9]{32}$/.test(entry.md5), 'manifest MD5 非法');
    assert(Number.isInteger(entry.visibleNodeCount) && entry.visibleNodeCount > 0, 'manifest visibleNodeCount 必须大于 0');
    assert(Array.isArray(entry.pageStack) && entry.pageStack.length === 1 && entry.pageStack[0] === entry.route.slice(1).split('?')[0], 'manifest pageStack 必须只保留当前 route');
    assert(entry.scrollTop === 0, 'manifest scrollTop 必须为 0');
    assert(Number.isInteger(entry.stableWaitMs) && entry.stableWaitMs >= STABLE_WAIT_MS, 'manifest stableWait 不足');
    assert(Array.isArray(entry.overlays) && entry.overlays.length === 0, 'manifest overlay 未清理');
    assert(entry.evidenceKind === EVIDENCE_KIND, '不得将受控帧标为真实数据或真机证据');
    const fixture = FIXTURES.find((item) => item.state === entry.state);
    assert(fixture && entry.route === fixture.route && entry.file.endsWith(fixture.file), 'manifest route/state/file 与 fixture 不匹配');
    assert(!states.has(entry.state), `state 重复: ${entry.state}`);
    assert(!hashes.has(entry.md5), `MD5 负控失败: ${entry.md5}`);
    states.add(entry.state); hashes.add(entry.md5);
  }
  for (const fixture of FIXTURES) assert(states.has(fixture.state), `manifest 缺少 ${fixture.state} 态`);
  return true;
}

async function sessionSnapshot(mp) {
  return mp.evaluate(function () {
    const data = (getApp() && getApp().globalData) || {};
    return { hasAuthorization: Boolean(data.authorization), hasUserId: Boolean(data.user_id), hasRole: Boolean(data.role), hasUserInfo: Boolean(data.userInfo), interceptedRequests: Number((globalThis.__q119Fixture || {}).requests || 0) };
  });
}

async function installSessionIsolation(mp) {
  await mp.evaluate(function () {
    const marker = globalThis.__q119Fixture || { requests: 0 };
    if (marker.installed) return;
    marker.installed = true;
    marker.originalRequest = wx.request;
    marker.originalGetLocation = wx.getLocation;
    wx.request = function () { marker.requests += 1; return { abort() {} }; };
    wx.getLocation = function () { marker.locations = Number(marker.locations || 0) + 1; return { abort() {} }; };
    globalThis.__q119Fixture = marker;
  });
}

async function assertPreCaptureSessionQuiescent(mp, before, delay) {
  await (delay || wait)(STABLE_WAIT_MS);
  validateSessionSnapshot(before, await sessionSnapshot(mp));
  return true;
}

async function restoreSessionIsolation(mp) {
  await mp.evaluate(function () {
    const marker = globalThis.__q119Fixture;
    if (marker && marker.originalRequest) wx.request = marker.originalRequest;
    if (marker && marker.originalGetLocation) wx.getLocation = marker.originalGetLocation;
    delete globalThis.__q119Fixture;
  });
}

async function resetAndReadViewportScrollTop(mp) {
  await mp.pageScrollTo({ scrollTop: 0, duration: 0 });
  return mp.evaluate(function () {
    return new Promise((resolve, reject) => {
      wx.createSelectorQuery().selectViewport().scrollOffset((result) => resolve(Number(result && result.scrollTop))).exec((error) => error && reject(error));
    });
  });
}

async function stableComposition(mp, page, fixture) {
  const scrollTop = await resetAndReadViewportScrollTop(mp);
  assert(scrollTop === 0, `${fixture.state} 视口 scrollTop 未归零: ${scrollTop}`);
  await wait(STABLE_WAIT_MS);
  const visible = await page.$(fixture.visible.selector);
  const exclusive = await page.$(fixture.exclusive.selector);
  assert(visible, `${fixture.state} 缺少首屏可见节点: ${fixture.visible.selector}`);
  assert(exclusive, `${fixture.state} 缺少态专属节点: ${fixture.exclusive.selector}`);
  if (fixture.visible.text) assert((await visible.text()).includes(fixture.visible.text), `${fixture.state} 可见节点文本不符`);
  if (fixture.exclusive.dataPath) assert((await exclusive.data(fixture.exclusive.dataPath)) === fixture.exclusive.expected, `${fixture.state} 态专属节点状态不符`);
  const pageStack = (await mp.pageStack()).map((item) => String(item.path || '').replace(/^\//, '').split('?')[0]);
  assert(pageStack.length === 1 && pageStack[0] === fixture.pagePath, `${fixture.state} pageStack 不匹配`);
  const overlays = [];
  for (const selector of OVERLAY_SELECTORS) if ((await page.$$(selector)).length) overlays.push(selector);
  assert(overlays.length === 0, `${fixture.state} 存在未清理 overlay: ${overlays.join(',')}`);
  const nodes = await page.$$('*');
  assert(nodes.length > 0, `${fixture.state} 已渲染 viewNode 数为 0`);
  return { pageStack, overlays, scrollTop, visibleNodeCount: nodes.length };
}

async function main() {
  validateFixturePlan(FIXTURES);
  assert(Number.isInteger(PORT) && PORT > 0 && PORT < 65536, '必须显式提供独占 WX_AUTO_PORT');
  assert(!fs.existsSync(OUTPUT_DIR), `拒绝覆盖既有目录: ${OUTPUT_DIR}`);
  fs.mkdirSync(OUTPUT_DIR, { recursive: true });
  let session;
  try {
    session = await openMiniProgram({ projectPath: PROJECT_PATH, port: PORT });
    const before = await sessionSnapshot(session.mp);
    await installSessionIsolation(session.mp);
    await assertPreCaptureSessionQuiescent(session.mp, before);
    const entries = [];
    for (const fixture of FIXTURES) {
      const page = await session.mp.reLaunch(fixture.route);
      await fixture.apply(page);
      const composition = await stableComposition(session.mp, page, fixture);
      const file = path.join(OUTPUT_DIR, fixture.file);
      await session.mp.screenshot({ path: file });
      assert(fs.existsSync(file) && fs.statSync(file).size > 0, `${fixture.state} 没有产生截图文件`);
      entries.push({ route: fixture.route, state: fixture.state, file, md5: md5(file), scrollTop: composition.scrollTop, stableWaitMs: STABLE_WAIT_MS, overlays: composition.overlays, pageStack: composition.pageStack, visibleNodeCount: composition.visibleNodeCount, evidenceKind: EVIDENCE_KIND });
    }
    const after = await sessionSnapshot(session.mp);
    validateSessionSnapshot(before, after); validateManifest(entries);
    fs.writeFileSync(path.join(OUTPUT_DIR, 'manifest.json'), `${JSON.stringify({ evidenceKind: EVIDENCE_KIND, session: { before, after }, entries }, null, 2)}\n`);
    process.stdout.write(`Q119/Q120 PASS ${entries.length}/${FIXTURES.length}\n`);
  } finally {
    if (session && session.mp) {
      await restoreSessionIsolation(session.mp).catch(() => {});
      if (typeof session.mp.disconnect === 'function') await session.mp.disconnect();
    }
  }
}

module.exports = { EVIDENCE_KIND, FIXTURES, STABLE_WAIT_MS, assertPreCaptureSessionQuiescent, resetAndReadViewportScrollTop, restoreSessionIsolation, validateFixturePlan, validateManifest, validateSessionSnapshot };
if (require.main === module) main().catch((error) => { process.stderr.write(`Q119/Q120 FAIL: ${error.stack || error.message}\n`); process.exitCode = 1; });
