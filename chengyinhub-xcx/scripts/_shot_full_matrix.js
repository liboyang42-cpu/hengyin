/*
 * 2026-08-05 全量截图采集：跑完 shot-matrix 的 221 项 actionable，按 role 落到
 * 玩家版 / 商家版两个目录。
 *
 * 与 0801 驱动的三点差别：
 *  1. shots 来自 shot-matrix.js，不是硬编码列表。
 *  2. DOM 断言失败 **不中断整轮** —— 断言红恰恰是我们要找的坏页证据，
 *     记进 manifest 继续跑；只有 DevTools 掉线才 abort。
 *  3. md5 去重：mp.screenshot 会返回陈旧帧（已实证 4 个不同态拍出同一张），
 *     撞 md5 就重拍一次，仍撞则标 stale-frame。
 *
 * 用法：
 *   SHOT_OUT=~/Desktop/城瘾全量截图_20260805 node scripts/_shot_full_matrix.js
 *   SHOT_ONLY=A01,B12 ...   只跑指定 id
 *   SHOT_PRIORITY=P0 ...    只跑某优先级
 */
process.env.UI_AUDIT_OUT = process.env.UI_AUDIT_OUT || '/tmp/_shot_infra_unused';
const infra = require('./_infra');
const { SHOTS } = require('./shot-matrix');
const SELECTORS = require('./selectors.json');
const FIXTURES = require('./fixtures.json');

const fs = infra.fs;
const path = infra.path;
const crypto = require('crypto');
const childProcess = require('child_process');
const wait = infra.wait;
const log = (m) => console.log(m);

const ROOT = process.env.SHOT_OUT || path.join(process.env.HOME, 'Desktop', '城瘾全量截图_20260805');
const DIRS = { player: '玩家版', guest: '玩家版', club: '玩家版', merchant: '商家版' };
const ONLY = process.env.SHOT_ONLY ? new Set(process.env.SHOT_ONLY.split(',').map((s) => s.trim())) : null;
const PRIORITY = process.env.SHOT_PRIORITY ? new Set(process.env.SHOT_PRIORITY.split(',')) : null;

const FIXTURE_NUMBER_FIELDS = new Set([
  'x', 'y', 'z', 'len', 'deg', 'v', 'i', 'mileage', 'nodeId', 'rot', 'tx0', 'ty0', 'tx1', 'ty1',
  'a', 'w', 'dur', 'c', 'h', 'dx', 'dy', 'left', 'delay', 'idx', 'gap', 'ty', 'fs', 'step', 'rank',
  'weight', 'n', 'memberId', 'topicId', 'avatarOverflow', 'rating', 'minAmout', 'distM', 'toId',
  'unitCost', 'quota', 'totalMileage', 'offset',
]);
const FIXTURE_BOOLEAN_FIELDS = new Set([
  'done', 'on', 'locked', 'fly', 'land', 'crowded', 'arrived', 'justWrote', 'completed', 'complete',
  'collected', 'stampDone', 'likeSubmitting', 'collapsible', '_coverFail', '_dot', '_acceptAccented',
  '_showAccept', '_canToggle', '_canDelete', '_canViewApplies', '_online', 'decisionReady',
  'legacyReadonly', 'depositOwed', 'depositRefundPending', 'checked', 'failed', 'sending', 'muted',
  '_dragging', 'fallback',
]);
const FIXTURE_TRUE_FIELDS = new Set(['loaded', 'active']);
const FIXTURE_ARRAY_FIELDS = new Set([
  'avatars', 'paras', 'choices', 'steps', 'expOptions', 'dirOptions', 'aiPrompts', 'chips', 'array2', 'hotkeys',
]);
const FIXTURE_OBJECT_FIELDS = new Set(['cmsActivity', 'card', 'playerTask', 'taskEvidence', 'submission']);
const FIXTURE_TEXT = {
  title: '外滩夜行计划',
  _title: '外滩夜行计划',
  templateTitle: '城市建筑线索',
  name: '河畔咖啡',
  nickname: '林野',
  memberNickname: '林野',
  displayName: '林野',
  customerDisplayName: '林野',
  merchantNick: '河畔咖啡',
  subtitle: '沿城市线索重新发现熟悉街区',
  sub: '沿城市线索重新发现熟悉街区',
  description: '沿街寻找建筑细节，完成今天的城市任务。',
  descriptionText: '沿街寻找建筑细节，完成今天的城市任务。',
  desc: '沿街寻找建筑细节，完成今天的城市任务。',
  contents: '今天沿着街区找到了一处新的城市细节。',
  content: '今天沿着街区找到了一处新的城市细节。',
  body: '沿着提示继续探索，下一处线索就在街角。',
  text: '城市线索已点亮',
  message: '城市线索已点亮',
  label: '城市探索',
  categoryName: '城市探索',
  category: '城市探索',
  catLabel: '城市探索',
  tplName: '城市建筑线索',
  playName: '外滩夜行',
  sportName: '城市漫游',
  playSubtitle: '已完成 2/3 个线索',
  address: '上海市黄浦区中山东一路',
  addressName: '外滩观景平台',
  city: '上海',
  distance: '1.8 km',
  duration: '90 分钟',
  players: '2-6 人',
  countdown: '今天 19:00 开始',
  countdownText: '今天 19:00 开始',
  started: '进行中',
  status: 'ACTIVE',
  state: 'ready',
  stateText: '进行中',
  statusText: '进行中',
  _statusText: '进行中',
  statusLabel: '进行中',
  openText: '营业中',
  waitText: '无需等待',
  sourceText: '城市主题',
  value: '城市探索',
  val: '城市探索',
  key: 'city-explore',
  k: 'city-explore',
  kind: 'city',
  code: 'CITY-001',
  missionCode: 'CITY_CLUE',
  tagCode: 'CITY_STYLE',
  tagValue: '城市观察',
  roleCode: 'PLAYER',
  chapterLabel: '第 1 章',
  clue: '留意建筑门楣上的年份',
  hint: '从入口左侧的铭牌开始寻找',
  evaluation: '已完成',
  completionStatus: 'COMPLETED',
  registrationStatus: 'CONFIRMED',
  verificationStatus: 'VERIFIED',
  bankName: '城市商业银行',
  path: '/pages/index/index',
  icon: 'pin',
  iconName: 'pin',
  poster: '/images/route_city_cover.png',
  motionSrc: '/images/route_city_cover.png',
  src: '/images/route_city_cover.png',
  color: '#8B5CF6',
};

function normalizeScreenshotFixture(value, field = '') {
  if (Array.isArray(value)) return value.map((item) => normalizeScreenshotFixture(item, field));
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, normalizeScreenshotFixture(item, key)]));
  }
  if (typeof value !== 'string' || !value.includes('示例')) return value;
  if (FIXTURE_ARRAY_FIELDS.has(field)) return [];
  if (FIXTURE_OBJECT_FIELDS.has(field)) return {};
  if (FIXTURE_TRUE_FIELDS.has(field)) return true;
  if (FIXTURE_BOOLEAN_FIELDS.has(field)) return false;
  if (field === 'opacity' || field === 'scale') return 1;
  if (field === 'blur') return 0;
  if (FIXTURE_NUMBER_FIELDS.has(field)) return field === 'rating' ? 4.8 : 24;
  return Object.prototype.hasOwnProperty.call(FIXTURE_TEXT, field) ? FIXTURE_TEXT[field] : '城市探索';
}

// 批次标签:同一目录下多批拍摄各写各的 manifest,互不覆盖
const BATCH_TAG = process.env.SHOT_BATCH || `b${process.pid}`;

const REPO_ROOT = path.resolve(infra.PROJECT, '..');

function digestSourceSnapshot(head, trackedDiff, untrackedEntries) {
  const hash = crypto.createHash('sha256');
  hash.update('head\0').update(String(head)).update('\0tracked-diff\0');
  hash.update(Buffer.isBuffer(trackedDiff) ? trackedDiff : Buffer.from(trackedDiff || ''));
  const entries = (untrackedEntries || []).slice().sort((a, b) => a.path.localeCompare(b.path));
  for (const entry of entries) {
    hash.update('\0untracked\0').update(String(entry.path)).update('\0');
    hash.update(Buffer.isBuffer(entry.content) ? entry.content : Buffer.from(entry.content || ''));
  }
  return hash.digest('hex');
}

function readSourceSnapshot() {
  const maxBuffer = 128 * 1024 * 1024;
  const head = childProcess.execFileSync('git', ['rev-parse', 'HEAD'], {
    cwd: REPO_ROOT, encoding: 'utf8', maxBuffer,
  }).trim();
  const trackedDiff = childProcess.execFileSync('git', ['diff', '--binary', 'HEAD', '--', 'chengyinhub-xcx'], {
    cwd: REPO_ROOT, maxBuffer,
  });
  const untrackedPaths = childProcess.execFileSync(
    'git', ['ls-files', '--others', '--exclude-standard', '-z', '--', 'chengyinhub-xcx'],
    { cwd: REPO_ROOT, encoding: 'utf8', maxBuffer },
  ).split('\0').filter(Boolean);
  const untrackedEntries = untrackedPaths.map((relative) => {
    const absolute = path.join(REPO_ROOT, relative);
    const content = fs.lstatSync(absolute).isSymbolicLink()
      ? Buffer.from(fs.readlinkSync(absolute))
      : fs.readFileSync(absolute);
    return { path: relative, content };
  });
  return {
    head,
    digest: digestSourceSnapshot(head, trackedDiff, untrackedEntries),
    dirty: trackedDiff.length > 0 || untrackedEntries.length > 0,
    trackedDiffBytes: trackedDiff.length,
    untrackedCount: untrackedEntries.length,
  };
}

const sourceSnapshot = readSourceSnapshot();

const manifest = {
  purpose: '2026-08-05 全量 UI 截图矩阵；开发者工具受控态，非真机证据',
  project: infra.PROJECT,
  head: sourceSnapshot.head,
  sourceDigest: sourceSnapshot.digest,
  dirty: sourceSnapshot.dirty,
  sourceSnapshot: {
    trackedDiffBytes: sourceSnapshot.trackedDiffBytes,
    untrackedCount: sourceSnapshot.untrackedCount,
  },
  viewport: { width: 624, height: 1352 },
  port: infra.PORT,
  startedAt: new Date().toISOString(),
  shots: [],
  consoleErrors: [],
  failure: null,
};
function writeManifest() {
  manifest.updatedAt = new Date().toISOString();
  // ★每批必须写**独立文件名**。原来固定写 manifest.json,分批拍时后一批直接覆盖前一批 ——
  //   2026-08-19 实测:三批拍完只剩最后一批的 manifest(shots=0),前 170 张的出身记录全没了,
  //   而出身门禁正是靠 manifest 的 head 判「这批图是不是同一个 commit 拍的」⇒ 覆盖=门禁失效。
  //   _merge_manifests.js 本来就按 manifest*.json 通配读取,这里给唯一名即可。
  fs.writeFileSync(path.join(ROOT, `manifest.${BATCH_TAG}.json`), JSON.stringify(manifest, null, 2));
}

const md5seen = new Map();
let lastRoute = null; // 上一张拍的是哪个路由，用来判断要不要中转清实例
const md5of = (f) => crypto.createHash('md5').update(fs.readFileSync(f)).digest('hex');

async function screenshotWithRetry(take, options = {}) {
  const attempts = Number.isInteger(options.attempts) ? options.attempts : 3;
  const delayMs = Number.isFinite(options.delayMs) ? options.delayMs : 350;
  const pause = options.pause || wait;
  let lastError;
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      return await take();
    } catch (error) {
      lastError = error;
      if (attempt < attempts) await pause(delayMs);
    }
  }
  throw lastError;
}

async function readFixtureValue(scope, key) {
  const [root, ...rest] = String(key).split('.');
  let value = await scope.data(root).catch(() => undefined);
  // miniprogram-automator 的 data('key') 会把合法 null 回成 undefined；
  // 回读整份 data 才能区分「键存在且为 null」与「键不存在」。
  if (value === undefined) {
    const all = await scope.data().catch(() => undefined);
    if (all && Object.prototype.hasOwnProperty.call(all, root)) value = all[root];
  }
  for (const segment of rest) value = value == null ? undefined : value[segment];
  return value;
}

async function resolveComponentPath(root, segments, label) {
  let scope = root;
  for (const rawSegment of segments) {
    const segment = typeof rawSegment === 'string'
      ? { selector: rawSegment, index: 0 }
      : (rawSegment || {});
    const selector = String(segment.selector || '');
    const index = Number.isInteger(segment.index) && segment.index >= 0 ? segment.index : 0;
    const matches = selector && scope && typeof scope.$$ === 'function'
      ? await scope.$$(selector).catch(() => [])
      : [];
    const target = Array.isArray(matches) ? matches[index] : null;
    if (!target) throw new Error(`${label} path 不存在: ${selector}[${index}]`);
    scope = target;
  }
  return scope;
}

async function resolveFixtureScope(page, target) {
  if (!target) return page;
  if (Array.isArray(target)) return resolveComponentPath(page, target, 'fixture');
  const scope = await page.$(target);
  if (!scope) throw new Error(`fixture target 不存在: ${target}`);
  return scope;
}

async function resolveAssertionScope(page, fixtureScope, want = {}) {
  if (Array.isArray(want.assertPath) && want.assertPath.length) {
    return resolveComponentPath(page, want.assertPath, 'assert');
  }
  if (!want.assertTarget) return fixtureScope;
  const target = await page.$(want.assertTarget);
  if (!target) throw new Error(`assert target 不存在: ${want.assertTarget}`);
  return target;
}

// 「黑屏」的真判据是像素，不是 DOM selector —— 矩阵里 fixtureVerified:false 的
// expect 大量是未校准假设（A01 首页渲染完好却因为找不到 .cy-page-title 报红）。
// 直接问图：正常页有文字和卡片，最大同色占比压不到 0.97 以上。
function blankness(file) {
  const img = infra.readPng(file);
  const bins = new Map();
  let n = 0;
  // 跳过顶部状态栏/胶囊和底部 home indicator，它们在任何页面都一样
  for (let y = 60; y < img.height - 40; y += 6) {
    for (let x = 4; x < img.width - 4; x += 6) {
      const [r, g, b] = img.px(x, y);
      const k = `${r >> 3},${g >> 3},${b >> 3}`; // 量化到 5bit，抗渐变噪声
      bins.set(k, (bins.get(k) || 0) + 1);
      n += 1;
    }
  }
  let top = 0;
  let topKey = '';
  for (const [k, v] of bins) if (v > top) { top = v; topKey = k; }
  const ratio = top / n;
  const [r, g, b] = topKey.split(',').map((v) => Number(v) << 3);

  // ⚠️ 上面那个量化主色**只能用来判黑屏**，不能用来判饱和度。
  // 5bit 量化会把邻近色聚成一桶、再取桶中心还原，极暗色上误差被放大：
  // 实测 club/create 的真实主色 (23,23,24) 饱和度 2%，量化后报成 20%；
  // square/list 的 #0A0300 更夸张，量化成 (8,0,0) 报 100%。
  // 所以另存一份**不量化**的精确主色，专供色相/饱和度类判据用。
  const exact = new Map();
  for (let y = 60; y < img.height - 40; y += 6) {
    for (let x = 4; x < img.width - 4; x += 6) {
      const px = img.px(x, y);
      const key = `${px[0]},${px[1]},${px[2]}`;
      exact.set(key, (exact.get(key) || 0) + 1);
    }
  }
  let etop = 0;
  let eKey = '0,0,0';
  for (const [k, v] of exact) if (v > etop) { etop = v; eKey = k; }
  const [er, eg, eb] = eKey.split(',').map(Number);
  const emax = Math.max(er, eg, eb);
  const emin = Math.min(er, eg, eb);
  const el = (emax + emin) / 2 / 255;
  const esat = emax === emin ? 0
    : ((emax - emin) / 255) / (el > 0.5 ? (2 - (emax + emin) / 255) : ((emax + emin) / 255)) * 100;
  // 阈值实测标定：正常深色首页 A01 的主色占比就有 0.9535（大面积深底 + 少量文字），
  // 所以单看占比会把好页判红。真黑屏的另一半特征是**几乎没有别的颜色**。
  const verdict = (ratio > 0.995 && bins.size <= 3) ? 'blank'
    : (ratio > 0.985 && bins.size <= 12) ? 'near-blank'
      : 'has-content';
  return {
    dominantRatio: Number(ratio.toFixed(4)),
    dominantRgb: [r, g, b],           // 量化值：判黑屏用
    distinctColors: bins.size,
    exactRgb: [er, eg, eb],           // 精确值：判色相/饱和度用
    exactRatio: Number((etop / n).toFixed(4)),
    exactSaturation: Number(esat.toFixed(1)),
    verdict,
  };
}

function applyEvidenceGates(rec, want) {
  // 编译错误帧常常仍能被 screenshot 正常写盘，但逻辑层没有真实页面节点。
  // 普通页面至少应有 8 个 view；WebGL/cover-view 页面主体不在 view 树里，单独降到 1。
  const viewNodeMin = Number.isFinite(want.viewNodeMin)
    ? want.viewNodeMin
    : (String(want.selector).includes('cover-view') ? 1 : 8);
  rec.viewNodeGate = { min: viewNodeMin, pass: rec.viewNodeCount >= viewNodeMin };
  if (!rec.viewNodeGate.pass) {
    rec.dom.pass = false;
    rec.viewNodeFailure = `viewNodeCount=${rec.viewNodeCount}，低于 ${viewNodeMin}`;
  }
  if (rec.injectUnstable && rec.injectUnstable.length) {
    rec.dom.pass = false;
    rec.fixtureFailure = `fixture 未稳定兑现: ${rec.injectUnstable.join(',')}`;
  }
  return rec;
}

function expectedRouteEvidence(route) {
  const [rawPath, rawQuery = ''] = String(route).split('?');
  return {
    path: '/' + rawPath.replace(/^\//, ''),
    query: Object.fromEntries(new URLSearchParams(rawQuery)),
  };
}

function routeEvidenceMatches(expected, actual) {
  if (expected.path !== actual.path) return false;
  // ⚠️ 两边编码不同源:expected 由 URLSearchParams 解析(**已解码**),actual 来自
  // getCurrentPages().options(**未解码**)。中文 query 于是恒不相等 ——「路由未兑现」假红,
  // 2026-08-18 实测打死 A18/A20/A21(?keyword=城市)与 E03(?topicName=苏河湾夜行)四行。
  // 归一到解码后再比;解不开的串(单个 % 之类)保持原样,不许静默吞成 undefined。
  const decode = (value) => {
    try { return decodeURIComponent(String(value)); } catch (e) { return String(value); }
  };
  return Object.entries(expected.query).every(([key, value]) => decode(actual.query[key]) === decode(value));
}

function allowFrameException(want, detectedDefect) {
  return Boolean(want.allowFlatDarkEdge)
    && /edge column is \d+% flat DevTools-chrome grey/.test(String(detectedDefect));
}

// 身份要在 reLaunch 之前注入：只改 page data 不改 storage，原生导航栏还留在
// 上一个角色的主题上，会把 harness 假象拍成产品 bug。
/**
 * 身份回读的判据(纯函数,便于负控):写完 storage/globalData 之后**从对面读回来**再比。
 * 2026-08-18 实证:换 DevTools 实例后的第一张图会继承上一实例的身份 —— B21 标着 role=player
 * 却拍出商家浅色视图(isMerchantViewer=true)。setIdentity 里的 `if (app && app.globalData)`
 * 在冷启动那一刻可能整段跳过,写了 storage 却没改 globalData,而 getUserRole() 优先读 globalData
 * ⇒ 旧身份原样活到下一张图上。写完就走 = 回执;读回来才是观测。
 */
function identityMatches(want, seen) {
  if (!seen) return false;
  if (String(seen.globalRole || '') !== String(want.role || '')) return false;
  if (Number(seen.globalType) !== Number(want.userType)) return false;
  if (String(seen.storageRole || '') !== String(want.role || '')) return false;
  if (Number(seen.storageType) !== Number(want.userType)) return false;
  if (Boolean(seen.hasAuth) !== (Number(want.userType) !== 0)) return false;
  if (Number(want.userType) === 0 && seen.hasIdentitySnapshot) return false;
  return true;
}

async function readIdentity(mp) {
  return mp.evaluate(() => {
    const app = getApp();
    const g = (app && app.globalData) || {};
    return {
      globalRole: g.role,
      globalType: g.user_type,
      storageRole: wx.getStorageSync('role'),
      storageType: wx.getStorageSync('user_type'),
      hasAuth: Boolean(wx.getStorageSync('authorization')),
      hasIdentitySnapshot: Boolean(
        g.user_id || g.open_id || g.avatar || g.nickname
        || wx.getStorageSync('user_id') || wx.getStorageSync('open_id')
        || wx.getStorageSync('avatar') || wx.getStorageSync('nickname')
        || wx.getStorageSync('role_permission_cache_v1')
      ),
    };
  }).catch(() => null);
}

async function settlePendingPrivacy(mp) {
  const state = await mp.evaluate(() => {
    const app = getApp();
    const pending = app && Array.isArray(app._privacyResolvers) ? app._privacyResolvers.length : 0;
    if (pending && typeof app.resolvePrivacyAuthorization === 'function') {
      app.resolvePrivacyAuthorization({ event: 'disagree', reason: 'screenshot_fixture_reset' });
    }
    return {
      settled: pending,
      pending: app && Array.isArray(app._privacyResolvers) ? app._privacyResolvers.length : 0,
      navigationPending: Boolean(app && app._privacyNavigationPending),
    };
  });
  if (!state || state.pending !== 0) {
    throw new Error(`隐私授权 resolver 未清空: ${JSON.stringify(state)}`);
  }
  if (state.navigationPending) {
    throw new Error('隐私授权导航仍在进行，拒绝污染下一张截图');
  }
  return state;
}

async function setIdentity(mp, role) {
  const map = {
    merchant: { role: 'merchant', userType: 2, snapshot: null },
    // club/create 的闸是 roleGuard.hasSnapshot() && !isClubLeader() —— 只写 role 过不了，
    // 必须给一份非空 permission 的快照，否则拍出来是被 redirect 的 apply 页。
    club: { role: 'club', userType: 3, snapshot: { role: 'club', isClubLeader: true, permission: { publishTheme: true }, usage: {} } },
    player: { role: 'user', userType: 1, snapshot: null },
    guest: { role: '', userType: 0, snapshot: null }
  };
  const id = map[role] || map.player;
  const write = () => mp.evaluate((r, t, snap) => {
    const app = getApp();
    if (t === 0) {
      // 游客夹具必须清完整会话，不能只删 token：否则旧 user_id/avatar/nickname 与
      // roleGuard 闭包快照会把匿名页面拍成上一个账号的状态。
      try {
        if (app && typeof app.getSession === 'function') app.getSession().clearSession();
      } catch (e) {}
      ['authorization', 'open_id', 'user_id', 'avatar', 'nickname'].forEach(function (key) {
        try { wx.removeStorageSync(key); } catch (e) {}
      });
    } else {
      wx.setStorageSync('authorization', 'dev-token');
      wx.setStorageSync('open_id', 'dev-openid');
      wx.setStorageSync('user_id', 1);
    }
    wx.setStorageSync('role', r);
    wx.setStorageSync('user_type', t);
    // ⚠️ roleGuard 有模块内闭包 _cache，_readStorage() 先返 _cache —— 光写 storage 不生效，
    // 上一个角色的权限快照会活到下一张图上（C40 club/create 被旧玩家快照 redirect 即此因）。
    // clear() 同时清 _cache 和 storage，所以必须「先 clear 再种」，顺序不能反。
    // ⚠️ require 只有裸路径解析得到：'./utils/roleGuard.js' 与 '/utils/roleGuard.js' 都会抛。
    try { require('utils/roleGuard.js').clear(); } catch (e) {}
    if (snap) { try { wx.setStorageSync('role_permission_cache_v1', snap); } catch (e) {} }
    if (app && app.globalData) {
      app.globalData.role = r;
      app.globalData.user_type = t;
      app.globalData.authorization = t === 0 ? '' : 'dev-token';
      app.globalData.open_id = t === 0 ? '' : 'dev-openid';
      app.globalData.user_id = t === 0 ? 0 : 1;
      if (t === 0) {
        app.globalData.avatar = '';
        app.globalData.nickname = '';
        delete app.globalData.userInfo;
      }
    }
  }, id.role, id.userType, id.snapshot);

  // 写 → 回读 → 不一致重写,三轮不成就红。**不通过的图不许进这一批**:
  // 拍一张身份错的图比少拍一张坏得多(它会被当成产品缺陷去修)。
  let seen = null;
  for (let round = 0; round < 3; round += 1) {
    await write();
    await wait(120);
    seen = await readIdentity(mp);
    if (identityMatches(id, seen)) return seen;
  }
  throw new Error(`身份未兑现(要 ${role}=${id.role}/${id.userType},回读 ${JSON.stringify(seen)})`);
}

async function capture(mp, shot) {
  const dir = path.join(ROOT, DIRS[shot.role] || '玩家版');
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, shot.file);
  const rec = { id: shot.id, file: shot.file, route: shot.route, role: shot.role, state: shot.state, priority: shot.priority, status: 'pending' };

  await settlePendingPrivacy(mp);
  await setIdentity(mp, shot.role);
  // ★ 连续两张同路由时，reLaunch 会复用页面实例，上一张 setData 进去的状态残留下来
  // —— 于是 D28(normal) 拍出了 D30(empty) 的样子，两张 md5 撞在一起。
  // 单独跑 D28/D30 一切正常，只有批量按顺序跑才复现，查了很久才定位到。
  // 中转一次别的路由强制重建实例。
  if (lastRoute && lastRoute.split('?')[0] === shot.route.split('?')[0]) {
    // 光换路由不够：渲染层还留着上一张的帧，下一张拍出来会跟它逐字节相同。
    // 实测受影响的不止一处 —— member/index、userinfo、merchantapply2 都是同路由多态。
    // 中转页里也滚一下逼渲染层刷新，再回来重建目标页。
    // 隐私页不是中性中转页：若此时有待决授权，它的 onUnload 会按“不同意”结算，
    // 直接污染下一张需要定位/相机能力的状态。协议页无网络与隐私 API，可安全刷新实例。
    await mp.reLaunch('/pages/agreement/index?type=user_agreement').catch(() => {});
    await wait(400);
    await mp.evaluate(() => wx.pageScrollTo({ scrollTop: 2, duration: 0 })).catch(() => {});
    await wait(200);
    await mp.evaluate(() => wx.pageScrollTo({ scrollTop: 0, duration: 0 })).catch(() => {});
    await wait(400);
  }
  lastRoute = shot.route;
  const expectedRoute = expectedRouteEvidence(shot.route);
  const readRouteEvidence = async (currentPage) => ({
    path: '/' + String(typeof currentPage.path === 'function' ? await currentPage.path() : currentPage.path).replace(/^\//, ''),
    query: await mp.evaluate(() => {
      const pages = getCurrentPages();
      return pages.length ? (pages[pages.length - 1].options || {}) : {};
    }).catch(() => ({})),
  });
  let page = await mp.reLaunch(shot.route);
  await wait(2200);
  let actualRoute = await readRouteEvidence(page);
  rec.actualPath = actualRoute.path;
  rec.actualQuery = actualRoute.query;
  if (!routeEvidenceMatches(expectedRoute, actualRoute)) {
    // reLaunch 偶发静默留在旧页（实拍 C47/C58 曾把首页当目标页拍下且退出 0）。
    // 再导航一次并从 DevTools 回读真实 path/query；仍不一致就立即红，禁止向错误页注 fixture。
    page = await mp.reLaunch(shot.route);
    await wait(2200);
    actualRoute = await readRouteEvidence(page);
    rec.actualPath = actualRoute.path;
    rec.actualQuery = actualRoute.query;
    if (!routeEvidenceMatches(expectedRoute, actualRoute)) {
      throw new Error(`路由未兑现: expected=${JSON.stringify(expectedRoute)} actual=${JSON.stringify(actualRoute)}`);
    }
  }

  // 用校准过的 fixture（字段名对齐页面真实 data 声明）；矩阵原 data 只作兜底
  const fx = FIXTURES[shot.id];
  const fixtureTarget = (fx && fx.target) || shot.fixtureTarget;
  const scope = await resolveFixtureScope(page, fixtureTarget);
  if (fixtureTarget) rec.fixtureTarget = fixtureTarget;
  const rawInject = (fx && fx.data) || shot.data;
  const inject = rawInject ? normalizeScreenshotFixture(rawInject) : rawInject;
  rec.fixtureKeys = [];
  if (inject && typeof inject === 'object') rec.fixtureKeys = Object.keys(inject).sort();
  if (fx && fx.dropped && fx.dropped.length) rec.droppedKeys = fx.dropped;
  if (inject) {
    // 注入 → 回读 → 不一致就重注，收敛为止。
    // ⚠️ 定时注入（原本注三次、最后一次在 ~650ms）不够：页面的接口回调什么时候
    // 回来是不确定的，回来就把注入的状态覆盖掉，于是 empty/error 态拍出来和
    // normal 态一模一样（md5 撞在一起）。2026-08-05 实测 A15/A16 即如此 ——
    // 注进去 nearbyClubs 1 条，回读是 0，被迟到的响应盖了。
    // 定时改不出可靠时机（响应快慢随网络变），只有「回读到我要的值」才是观测。
    // ★ 判据是「稳定持续」，不是「注完那一刻对」：A15 实测第一轮回读就一致，
    // 但响应在回读之后才回来，截图拍到的还是被盖回去的空态。所以要复读一次
    // ——间隔够长，能盖过接口往返——两次都一致才算注住。
    const keys = Object.keys(inject);
    const readBad = async () => {
      const bad = [];
      for (const k of keys) {
        // ⚠️ 路径式 key（'formData.validationMethod'）：automator 的 page.data(path)
        // 不认这种写法，回读永远拿不到值 ⇒ 收敛循环判「没注住」，一轮轮重注到超时，
        // 最后那张图其实是注进去了的、但被判成 unstable。改成读根对象再自己走路径。
        const got = await readFixtureValue(scope, k);
        if (JSON.stringify(got) !== JSON.stringify(inject[k])) bad.push(k);
      }
      return bad;
    };
    let bad0 = keys;
    for (let round = 0; round < 6; round += 1) {
      // 第一轮整体注；之后只补没注住的键，且逐个注。
      // ⚠️ A15 实测：整体一次 setData 十个键，nearbyClubs 落地就是 []；
      // 同样的值单独 setData 就正常渲染出 club-grid。原因没查到（automator
      // 侧的批量行为），但逐键补注是稳定可复现的解法。
      if (round === 0) await scope.setData(inject);
      else for (const k of bad0) await scope.setData({ [k]: inject[k] });
      await wait(200);
      let bad = await readBad();
      if (!bad.length) { await wait(700); bad = await readBad(); }
      rec.injectRounds = round + 1;
      if (!bad.length) { delete rec.injectUnstable; break; }
      bad0 = bad;
      rec.injectUnstable = bad; // 最后一轮仍不一致就留在 manifest 里，别静默
    }
    await wait(120);
  }
  // 交互态优先走页面自己的公开方法，避免只 setData 出一个产品逻辑永远到不了的壳。
  // args 按 automator 的 callMethod(name, ...args) 透传；任一方法失败即本图 error。
  if (fx && Array.isArray(fx.calls) && fx.calls.length) {
    rec.callsApplied = [];
    for (const action of fx.calls) {
      const args = Array.isArray(action.args) ? action.args : [];
      await page.callMethod(action.method, ...args);
      await wait(Number(action.waitMs) || 200);
      rec.callsApplied.push(action.method);
    }
  }
  // 断言用 selectors.json（按 wxml 真实结构生成），矩阵原始 expect 只留作对照。
  // 正向 selector 说"该状态该渲染什么"，forbid 说"该状态不该同时渲染什么" ——
  // normal 态的正向锚点是页面根容器（弱），真正抓得住的是 forbid。
  const want = SELECTORS[shot.id] || shot.expect;
  const assertScope = await resolveAssertionScope(page, scope, want);
  if (want.assertTarget) rec.assertTarget = want.assertTarget;
  if (Array.isArray(want.assertPath) && want.assertPath.length) rec.assertPath = want.assertPath;
  if (Number.isFinite(shot.scrollTop)) {
    const scrollSelector = want.scrollSelector || '';
    const sv = scrollSelector ? await page.$(scrollSelector).catch(() => null) : null;
    if (sv && typeof sv.scrollTo === 'function') {
      const before = Number(await sv.property('scrollTop').catch(() => 0)) || 0;
      await sv.scrollTo(0, shot.scrollTop);
      await wait(220);
      const after = Number(await sv.property('scrollTop').catch(() => 0)) || 0;
      rec.scrollVia = `scroll-view:${scrollSelector}`;
      rec.scrollApplied = { before, after, moved: after > before };
    } else {
      const before = Number(await page.scrollTop().catch(() => 0)) || 0;
      await mp.pageScrollTo(shot.scrollTop);
      await wait(220);
      const after = Number(await page.scrollTop().catch(() => 0)) || 0;
      rec.scrollVia = 'pageScrollTo';
      rec.scrollApplied = { before, after, moved: after > before };
    }
  }

  const countOf = async (sel) => {
    try { const n = await assertScope.$$(sel); return Array.isArray(n) ? n.length : 0; } catch (_) { return -1; }
  };
  const count = await countOf(want.selector);
  rec.dom = { selector: want.selector, count, min: want.min, weak: Boolean(want.weak), pass: count >= want.min };
  let visibleEvidence = null;
  if ((Array.isArray(want.textIncludes) && want.textIncludes.length) || Number.isFinite(want.visibleMin)) {
    const nodes = await assertScope.$$(want.selector).catch(() => []);
    const windowInfo = await mp.evaluate(() => {
      const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
      return { width: info.windowWidth, height: info.windowHeight };
    });
    visibleEvidence = await collectVisibleNodeEvidence(
      nodes,
      Number(windowInfo && windowInfo.width),
      Number(windowInfo && windowInfo.height),
    );
  }
  if (Array.isArray(want.textIncludes) && want.textIncludes.length) {
    const missing = want.textIncludes.filter((text) => !visibleEvidence.text.includes(text));
    rec.textEvidence = { expected: want.textIncludes, missing };
    if (missing.length) {
      rec.dom.pass = false;
      rec.textFailure = `可见文字缺失: ${missing.join(' / ')}`;
    }
  }
  if (Number.isFinite(want.visibleMin)) {
    const visibleCount = visibleEvidence.count;
    rec.visibility = { count: visibleCount, min: want.visibleMin };
    if (visibleCount < want.visibleMin) {
      rec.dom.pass = false;
      rec.visibilityFailure = `视口内可见节点 ${visibleCount}<${want.visibleMin}`;
    }
  }
  if (Number.isFinite(shot.scrollTop) && (!rec.scrollApplied || !rec.scrollApplied.moved)) {
    rec.dom.pass = false;
    rec.scrollFailure = `目标滚动 ${shot.scrollTop} 未发生位移`;
  }
  // scrolled 证据不能只证明「滚动条动过」和「DOM 在某处存在」；指定正文锚点时，
  // 还必须证明它真的进入本次截图视口。否则 scrollTop=1 + 屏外正文也会假绿。
  if (Number.isFinite(shot.scrollTop) && want.viewportSelector) {
    try {
      const anchor = await assertScope.$(want.viewportSelector);
      const [offset, size, windowInfo] = await Promise.all([
        anchor && anchor.offset(),
        anchor && anchor.size(),
        mp.evaluate(() => {
          const info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
          return { height: info.windowHeight };
        }),
      ]);
      const documentTop = Number(offset && offset.top);
      const height = Number(size && size.height);
      const pageScroll = rec.scrollVia === 'pageScrollTo' ? Number(rec.scrollApplied && rec.scrollApplied.after) || 0 : 0;
      const top = documentTop - pageScroll;
      const bottom = top + height;
      const viewportHeight = Number(windowInfo && windowInfo.height);
      const visible = Boolean(anchor) && Number.isFinite(top) && height > 0
        && Number.isFinite(viewportHeight) && bottom > 0 && top < viewportHeight;
      rec.viewportAnchor = { selector: want.viewportSelector, top, bottom, viewportHeight, visible };
      if (!visible) {
        rec.dom.pass = false;
        rec.scrollFailure = `滚动后正文 ${want.viewportSelector} 未进入截图视口`;
      }
    } catch (error) {
      rec.dom.pass = false;
      rec.viewportAnchor = { selector: want.viewportSelector, visible: false, error: error.message };
      rec.scrollFailure = `无法证明滚动正文进入截图视口: ${error.message}`;
    }
  }
  try {
    const viewNodes = await page.$$('view');
    rec.viewNodeCount = Array.isArray(viewNodes) ? viewNodes.length : 0;
  } catch (_) {
    rec.viewNodeCount = -1;
  }
  applyEvidenceGates(rec, want);
  if (want.forbid && want.forbid.length) {
    rec.forbid = {};
    for (const f of want.forbid) rec.forbid[f] = await countOf(f);
    const violated = Object.entries(rec.forbid).filter(([, n]) => n > 0).map(([k]) => k);
    rec.forbidViolated = violated;
    if (violated.length) rec.dom.pass = false;
  }
  rec.matrixExpect = shot.expect;

  // 断言红也要拍 —— 那张图就是缺陷证据本身
  await screenshotWithRetry(async () => {
    await mp.screenshot({ path: file });
    if (!fs.existsSync(file) || fs.statSync(file).size === 0) {
      throw new Error(`screenshot bridge 没出图: ${shot.file}`);
    }
  });

  let md5 = md5of(file);
  if (md5seen.has(md5)) {
    // ⚠️ 只「等一会儿再拍一次」救不回陈旧帧：C33/C34 实测 DOM 明明不同
    // (.card-cont 一个 3 个节点、一个 2 个)，两张图 md5 却逐字节相同。
    // mp.screenshot 拿的是渲染层的缓存帧，光等不会让它重绘。
    // ⇒ 滚一下再滚回来，逼渲染层产生新帧。
    await mp.evaluate(() => wx.pageScrollTo({ scrollTop: 1, duration: 0 }));
    await wait(260);
    await mp.evaluate(() => wx.pageScrollTo({ scrollTop: 0, duration: 0 }));
    await wait(560);
    await screenshotWithRetry(() => mp.screenshot({ path: file }));
    md5 = md5of(file);
  }
  rec.md5 = md5;
  rec.staleFrame = md5seen.has(md5) ? md5seen.get(md5) : null;
  if (!md5seen.has(md5)) md5seen.set(md5, shot.file);

  const detectedDefect = infra.frameDefect(file, manifest.viewport);
  // 全屏深色抽屉会合法占满左右边缘，像素形状与 DevTools 的深灰侧栏相似。
  // 只允许逐状态显式豁免，并把原始命中原因留在 manifest 供人工复核。
  const allowFlatDarkEdge = allowFrameException(want, detectedDefect);
  const defect = detectedDefect && !allowFlatDarkEdge ? detectedDefect : null;
  if (detectedDefect && allowFlatDarkEdge) rec.frameException = detectedDefect;
  rec.frame = defect || 'clean';
  rec.blank = blankness(file);
  rec.bytes = fs.statSync(file).size;
  rec.evidence = inject ? 'controlled-setData' : 'route-runtime';
  // 判定优先级：整页空白 > 帧被污染 > 状态没换（陈旧帧）> 状态没兑现 > 接近空白。
  if (rec.blank.verdict === 'blank') rec.status = 'blank';
  else if (defect) rec.status = 'frame-defect';
  else if (rec.staleFrame) rec.status = 'stale';
  else if (!rec.dom.pass) rec.status = 'state-unmet';
  else if (rec.blank.verdict === 'near-blank') rec.status = 'near-blank';
  else rec.status = 'ok';
  return rec;
}

async function run() {
  fs.mkdirSync(ROOT, { recursive: true });
  // 矩阵漏了 3 个路由（addressinfo / coop withdraw ×2），用 EXTRA 补而不改
  // shot-matrix.js —— 那份文件的 selftest 把 234 这个数字锁死了。
  const extra = process.env.SHOT_EXTRA ? JSON.parse(fs.readFileSync(process.env.SHOT_EXTRA, 'utf8')) : [];
  let todo = SHOTS.concat(extra).filter((s) => !s.blocked);
  if (PRIORITY) todo = todo.filter((s) => PRIORITY.has(s.priority));
  if (ONLY) todo = todo.filter((s) => ONLY.has(s.id));
  manifest.plan = { total: todo.length, player: todo.filter((s) => DIRS[s.role] === '玩家版').length, merchant: todo.filter((s) => DIRS[s.role] === '商家版').length };
  console.log(`计划采集 ${todo.length} 张（玩家 ${manifest.plan.player} / 商家 ${manifest.plan.merchant}）`);
  writeManifest();

  // 冷启动后 pageStack 一响应就开拍会崩：项目那时还在编译，拍完第一张连接就断。
  // 预热一次首页并多等一会儿，让编译落地。
  // 试过在这里预热（reLaunch 首页 + 等 12s）想躲开冷启动编译，反而每次都在
  // 随后的 mp.on 上断连。不预热是实测能跑的那条路，别再加回来。
  const boot = () => infra.launchFixed();

  let mp;
  try {
    mp = await boot();
    mp.on('console', (m) => { if (m.type === 'error') manifest.consoleErrors.push({ at: manifest.shots.length, msg: String(m.args || '').slice(0, 300) }); });
    for (let i = 0; i < todo.length; i += 1) {
      const s = todo[i];
      let rec;
      try {
        rec = await capture(mp, s);
      } catch (e) {
        rec = { id: s.id, file: s.file, route: s.route, role: s.role, state: s.state, priority: s.priority, status: 'error', error: String(e.message || e).slice(0, 300) };
        // DevTools 本身掉线才停；单页失败继续
        try { await mp.pageStack(); } catch (_) { manifest.failure = `DevTools 掉线于 ${s.id}`; manifest.shots.push(rec); writeManifest(); throw e; }
      }
      manifest.shots.push(rec);
      if (i % 5 === 0 || rec.status !== 'ok') writeManifest();
      const flags = [
        rec.blank ? `px${rec.blank.dominantRatio}` : null,
        rec.dom && !rec.dom.pass && rec.dom.count < rec.dom.min ? `dom(${rec.dom.selector})=${rec.dom.count}<${rec.dom.min}` : null,
        rec.viewNodeFailure || null,
        rec.fixtureFailure || null,
        rec.textFailure || null,
        rec.visibilityFailure || null,
        rec.forbidViolated && rec.forbidViolated.length ? `forbid!${rec.forbidViolated.join('+')}` : null,
        rec.staleFrame ? `stale=${rec.staleFrame}` : null,
        rec.frame && rec.frame !== 'clean' ? 'frame-defect' : null,
      ].filter(Boolean).join(' ');
      console.log(`[${i + 1}/${todo.length}] ${rec.status.toUpperCase()} ${s.id} ${s.file} ${flags}${rec.error ? ' :: ' + rec.error : ''}`);
    }
  } finally {
    manifest.finishedAt = new Date().toISOString();
    const c = manifest.shots.reduce((a, r) => { a[r.status] = (a[r.status] || 0) + 1; return a; }, {});
    manifest.summary = c;
    writeManifest();
    console.log('汇总:', JSON.stringify(c));
    if (mp) {
      await Promise.race([
        mp.reLaunch('/pages/index/index').catch(() => {}),
        new Promise((resolve) => setTimeout(resolve, 5000)),
      ]);
      try { mp.disconnect(); } catch (_) {}
    }
  }
}

function recordRunFailure(target, error, finishedAt = new Date().toISOString()) {
  const detail = error && (error.stack || error.message)
    ? (error.stack || error.message)
    : String(error);
  target.failure = String(detail).slice(0, 2000);
  target.finishedAt = finishedAt;
  return target;
}

async function collectVisibleNodeEvidence(nodes, viewportWidth, viewportHeight) {
  const evidence = await Promise.all((nodes || []).map(async (node) => {
    const [offset, size, text] = await Promise.all([
      node.offset().catch(() => null),
      node.size().catch(() => null),
      node.text().catch(() => ''),
    ]);
    const top = Number(offset && offset.top);
    const left = Number(offset && offset.left);
    const width = Number(size && size.width);
    const height = Number(size && size.height);
    const visible = Number.isFinite(top) && Number.isFinite(left) && width > 0 && height > 0
      && top + height > 0 && top < viewportHeight
      && left + width > 0 && left < viewportWidth;
    return { visible, text: visible ? String(text || '') : '' };
  }));
  const visible = evidence.filter((item) => item.visible);
  return { count: visible.length, text: visible.map((item) => item.text).join('\n') };
}

async function countVisibleNodes(nodes, viewportWidth, viewportHeight) {
  return (await collectVisibleNodeEvidence(nodes, viewportWidth, viewportHeight)).count;
}

module.exports = {
  blankness,
  applyEvidenceGates,
  expectedRouteEvidence,
  routeEvidenceMatches,
  allowFrameException,
  identityMatches,
  settlePendingPrivacy,
  screenshotWithRetry,
  readFixtureValue,
  resolveFixtureScope,
  resolveAssertionScope,
  recordRunFailure,
  digestSourceSnapshot,
  normalizeScreenshotFixture,
  collectVisibleNodeEvidence,
  countVisibleNodes,
};

// require 进来做自测时不要真的开跑
if (require.main === module) {
  run().catch((e) => {
    recordRunFailure(manifest, e);
    writeManifest();
    console.error('ABORT:', e.message);
    process.exit(1);
  });
}
