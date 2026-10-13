'use strict';

// Q121: route/page-stack/state fixture. This file only defines a controlled future
// capture driver; the Q121 run itself is intentionally offline and does not start it.
// It never writes identity, token, role, real data, location, or permission state.

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { openMiniProgram } = require('../tests/automator/harness');

const OUTPUT_DIR = process.env.Q121_SHOT_DIR || '/tmp/q121-route-state-fixture-0803';
const PORT = Number(process.env.WX_AUTO_PORT);
const PROJECT_PATH = path.resolve(__dirname, '..');
const EVIDENCE_KIND = 'controlled-fixture-not-real-data-or-device';

const FIXTURES = [
  {
    file: '01-normal.png',
    state: 'normal',
    route: '/pages/club/apply/index',
    pagePath: 'pages/club/apply/index',
    visible: { selector: '.h1', text: '主理人实名' },
    apply: (page) => page.setData({ mode: 'form', step: 1, stepTitle: '主理人实名', canNext: false, submitting: false })
  },
  {
    file: '02-form.png',
    state: 'form',
    route: '/pages/club/apply/index',
    pagePath: 'pages/club/apply/index',
    visible: { selector: '.h1', text: '组织经验' },
    apply: (page) => page.setData({ mode: 'form', step: 2, stepTitle: '组织经验', experience: '', canNext: false, submitting: false })
  },
  {
    file: '03-success.png',
    state: 'success',
    route: '/pages/club/apply/index',
    pagePath: 'pages/club/apply/index',
    visible: { selector: '.st-badge', text: '已生效' },
    apply: (page) => page.setData({ mode: 'done', submitting: false })
  },
  {
    file: '04-error.png',
    state: 'error',
    route: '/subpackageMember/coupon-qr/index?id=1',
    pagePath: 'subpackageMember/coupon-qr/index',
    visible: { selector: 'cy-qr-voucher', dataPath: 'state', expected: 'error' },
    apply: (page) => page.setData({ qrState: 'error', errMsg: '当前受控状态不可用', qrcodeUrl: '', useStatus: 0, countdown: 60, coupon: {} })
  },
  {
    file: '05-empty.png',
    state: 'empty',
    route: '/pages/coop/nearby/index',
    pagePath: 'pages/coop/nearby/index',
    visible: { selector: 'cy-empty' },
    apply: (page) => page.setData({ pageState: 'ready', merchants: [] })
  },
  {
    file: '06-loading.png',
    state: 'loading',
    route: '/pages/coop/nearby/index',
    pagePath: 'pages/coop/nearby/index',
    visible: { selector: 'cy-skeleton' },
    apply: (page) => page.setData({ pageState: 'loading', merchants: [] })
  }
];

const TRANSITIONS = [
  { from: 'normal', to: 'form', route: '/pages/club/apply/index' },
  { from: 'form', to: 'success', route: '/pages/club/apply/index' }
];

const wait = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function md5(file) {
  return crypto.createHash('md5').update(fs.readFileSync(file)).digest('hex');
}

function validateFixturePlan(fixtures, transitions) {
  const expectedStates = ['normal', 'form', 'success', 'error', 'empty', 'loading'];
  assert(Array.isArray(fixtures) && fixtures.length === expectedStates.length, 'fixture 必须正好覆盖六态');
  assert(fixtures.map((fixture) => fixture.state).join(',') === expectedStates.join(','), 'fixture 状态顺序必须是 normal/form/success/error/empty/loading');
  const states = new Set();
  for (const fixture of fixtures) {
    assert(typeof fixture.route === 'string' && fixture.route.startsWith('/'), `${fixture.state} route 非法`);
    assert(fixture.pagePath === fixture.route.slice(1).split('?')[0], `${fixture.state} pagePath 必须由 route 推导`);
    assert(typeof fixture.file === 'string' && fixture.file.endsWith('.png'), `${fixture.state} file 非法`);
    assert(fixture.visible && typeof fixture.visible.selector === 'string', `${fixture.state} 缺少 rendered visible selector`);
    assert(typeof fixture.apply === 'function', `${fixture.state} 缺少受控状态切换`);
    assert(!states.has(fixture.state), `${fixture.state} 重复`);
    states.add(fixture.state);
  }
  assert(Array.isArray(transitions) && transitions.length === 2, '必须有 normal→form→success 两次状态切换');
  for (const transition of transitions) {
    const from = fixtures.find((fixture) => fixture.state === transition.from);
    const to = fixtures.find((fixture) => fixture.state === transition.to);
    assert(from && to && from.route === transition.route && to.route === transition.route, `${transition.from}→${transition.to} 必须保持同一路由`);
  }
  return true;
}

function validateManifest(entries) {
  validateFixturePlan(FIXTURES, TRANSITIONS);
  assert(Array.isArray(entries) && entries.length === FIXTURES.length, 'manifest 必须正好包含六帧');
  const md5s = new Set();
  const states = new Set();
  for (const entry of entries) {
    assert(typeof entry.route === 'string' && entry.route.startsWith('/'), 'manifest route 非法');
    assert(typeof entry.state === 'string' && entry.state, 'manifest 缺少 state');
    assert(typeof entry.file === 'string' && entry.file.endsWith('.png'), 'manifest file 非法');
    assert(/^[a-f0-9]{32}$/.test(entry.md5), 'manifest MD5 非法');
    assert(Number.isInteger(entry.visibleNodeCount) && entry.visibleNodeCount > 0, 'manifest visibleNodeCount 必须大于 0');
    assert(entry.evidenceKind === EVIDENCE_KIND, '不得将受控帧标为真实数据或真机证据');
    assert(Array.isArray(entry.pageStack) && entry.pageStack.length === 1 && entry.pageStack[0] === entry.route.slice(1).split('?')[0], 'manifest pageStack 必须只保留当前 route');
    assert(!md5s.has(entry.md5), `MD5 重复: ${entry.md5}`);
    assert(!states.has(entry.state), `state 重复: ${entry.state}`);
    md5s.add(entry.md5);
    states.add(entry.state);
  }
  return true;
}

async function renderedNode(page, visible, state) {
  const nodes = await page.$$(visible.selector);
  assert(nodes.length === 1, `${state} 必须有且仅有一个已渲染节点: ${visible.selector}`);
  const node = nodes[0];
  if (visible.text) {
    const actual = await node.text();
    assert(actual.includes(visible.text), `${state} 已渲染节点文本不符: ${visible.selector}`);
  }
  if (visible.dataPath) {
    assert((await node.data(visible.dataPath)) === visible.expected, `${state} 已渲染节点状态不符: ${visible.selector}.${visible.dataPath}`);
  }
  return node;
}

async function routeStack(mp) {
  return (await mp.pageStack()).map((page) => String(page.path || '').replace(/^\//, '').split('?')[0]);
}

async function assertRouteStack(mp, fixture) {
  const stack = await routeStack(mp);
  assert(stack.length === 1 && stack[0] === fixture.pagePath, `${fixture.state} pageStack 不匹配: ${JSON.stringify(stack)}`);
  return stack;
}

async function visibleNodeCount(page) {
  const nodes = await page.$$('*');
  assert(nodes.length > 0, '已渲染 viewNode 数为 0');
  return nodes.length;
}

function follows(previous, fixture) {
  return TRANSITIONS.some((transition) => transition.from === previous.state && transition.to === fixture.state && transition.route === fixture.route);
}

async function captureFixture(mp, fixture, previous, outputDir) {
  const page = previous && follows(previous.fixture, fixture)
    ? previous.page
    : await mp.reLaunch(fixture.route);
  if (!previous || page !== previous.page) await wait(700);
  await fixture.apply(page);
  await wait(350);
  await renderedNode(page, fixture.visible, fixture.state);
  const pageStack = await assertRouteStack(mp, fixture);
  const file = path.join(outputDir, fixture.file);
  await mp.screenshot({ path: file });
  assert(fs.existsSync(file) && fs.statSync(file).size > 0, `${fixture.state} 没有产生截图文件`);
  return {
    page,
    fixture,
    entry: {
      route: fixture.route,
      state: fixture.state,
      file,
      md5: md5(file),
      visibleNodeCount: await visibleNodeCount(page),
      evidenceKind: EVIDENCE_KIND,
      pageStack
    }
  };
}

async function main() {
  validateFixturePlan(FIXTURES, TRANSITIONS);
  assert(Number.isInteger(PORT) && PORT > 0 && PORT < 65536, '必须显式提供独占 WX_AUTO_PORT');
  assert(!fs.existsSync(OUTPUT_DIR), `拒绝覆盖既有目录: ${OUTPUT_DIR}`);
  fs.mkdirSync(OUTPUT_DIR, { recursive: true });

  let session;
  try {
    session = await openMiniProgram({ projectPath: PROJECT_PATH, port: PORT });
    let previous;
    const entries = [];
    for (const fixture of FIXTURES) {
      previous = await captureFixture(session.mp, fixture, previous, OUTPUT_DIR);
      entries.push(previous.entry);
    }
    validateManifest(entries);
    fs.writeFileSync(path.join(OUTPUT_DIR, 'manifest.json'), `${JSON.stringify({ evidenceKind: EVIDENCE_KIND, entries }, null, 2)}\n`);
    process.stdout.write(`Q121 PASS ${entries.length}/${FIXTURES.length} ${path.join(OUTPUT_DIR, 'manifest.json')}\n`);
  } finally {
    if (session && session.mp && typeof session.mp.disconnect === 'function') {
      await session.mp.disconnect();
    }
  }
}

module.exports = { EVIDENCE_KIND, FIXTURES, TRANSITIONS, validateFixturePlan, validateManifest };

if (require.main === module) {
  main().catch((error) => {
    process.stderr.write(`Q121 FAIL: ${error.stack || error.message}\n`);
    process.exitCode = 1;
  });
}
