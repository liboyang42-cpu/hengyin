const fs = require('fs');
const net = require('net');
const path = require('path');
const zlib = require('zlib');
const { spawn, execSync } = require('child_process');
const {
  assertProjectLease,
  readProjectLease,
  writeProjectLease,
} = require('./lib/devtools-project-lease');

// ★ automator 惰性加载(2026-08-10)。原来这两行在顶层 require:
//   const automator = require('miniprogram-automator');
//   const MiniProgram = require('miniprogram-automator/out/MiniProgram').default;
// 后果:任何只想用本模块纯函数(fs/path/readPng/frameDefect…)的静态契约测试,
// 都被迫依赖 miniprogram-automator 这个 devDependency。而 CI 的
// 「Mini program static checks」job **故意没有 npm ci 步骤** —— 它是纯静态、零三方
// 依赖的快 job。于是 MODULE_NOT_FOUND 让整个测试文件在**加载期**就崩,
// 表现为 node --test 里一条 testCodeFailure + 总测试数莫名少几条(子测试没注册完),
// 而 macOS 本机因为 node_modules 里装着,永远是绿的。
// ⇒ 只有真要起 DevTools 的 launchFixed()/connect() 才需要它,推迟到那时再加载。
let _automator = null;
function getAutomator() {
  if (_automator) return _automator;
  _automator = require('miniprogram-automator');
  // 原顶层的 checkVersion monkeypatch 一并挪进来:DevTools 36.x 上不 patch 会炸 split。
  const MiniProgram = require('miniprogram-automator/out/MiniProgram').default;
  MiniProgram.prototype.checkVersion = async function () {};
  return _automator;
}

const PROJECT = path.resolve(__dirname, '..');
const CLI = process.env.DEVTOOLS_CLI || '/Applications/wechatwebdevtools.app/Contents/MacOS/cli';
const PORT = Number(process.env.WX_AUTO_PORT || 9624);
const OUT_DIR = process.env.UI_AUDIT_OUT;
const ONLY_SHOTS = process.env.UI_AUDIT_ONLY
  ? new Set(process.env.UI_AUDIT_ONLY.split(',').map((value) => value.trim()).filter(Boolean))
  : null;

if (!OUT_DIR) throw new Error('UI_AUDIT_OUT is required');

const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const manifest = {
  purpose: '2026-08-01 Wave-1 UI audit; controlled simulator states, not production proof',
  project: PROJECT,
  head: execSync('git rev-parse HEAD', { cwd: path.resolve(PROJECT, '..'), encoding: 'utf8' }).trim(),
  viewport: { width: 624, height: 1352 },
  device: '微信开发者工具 simulator (not real-device evidence)',
  port: PORT,
  startedAt: new Date().toISOString(),
  screenshots: [],
  consoleErrors: [],
  failure: null,
};
const projectLease = {
  projectPath: PROJECT,
  sourceSha: manifest.head,
  port: PORT,
};

function writeManifest() {
  manifest.updatedAt = new Date().toISOString();
  fs.writeFileSync(path.join(OUT_DIR, 'manifest.json'), JSON.stringify(manifest, null, 2));
}

function isPortFree(port) {
  const canBind = (host) => new Promise((resolve) => {
    const server = net.createServer();
    server.once('error', () => resolve(false));
    server.once('listening', () => server.close(() => resolve(true)));
    server.listen(port, host);
  });
  const canConnect = (host) => new Promise((resolve) => {
    const socket = net.createConnection({ host, port });
    const finish = (free) => {
      socket.destroy();
      resolve(free);
    };
    socket.once('connect', () => finish(false));
    socket.once('error', () => finish(true));
    socket.setTimeout(250, () => finish(true));
  });
  return Promise.all([
    canConnect('127.0.0.1'),
    canConnect('::1'),
  ]).then(([ipv4Free, ipv6Free]) => ipv4Free && ipv6Free);
}

async function launchFixed() {
  // 端口被占用有两种情况：别的 worktree 抢了同一个端口（该拦），或者本项目上一批
  // 跑完后 disconnect 了、DevTools 实例还留着（该复用）。分批采集时必然是后者 ——
  // 220 张要分十几批跑，每批都重开一个实例既慢又容易把模拟器子系统搞坏
  // （skill 里那条「短时间内反复 kill+relaunch 会 simulator launch failed」）。
  // ⇒ 先核对由本驱动写入的 project+HEAD 租约，再探活。只有 pageStack 会把别的
  // worktree（同 AppID、同路由）误认成本项目，产生最危险的跨工作树假绿。
  if (!(await isPortFree(PORT))) {
    try {
      const existingLease = readProjectLease(PORT);
      if (!existingLease) throw new Error('端口存在 DevTools，但没有当前驱动签发的 project 租约');
      assertProjectLease(projectLease, existingLease);
      const existing = await getAutomator().connect({ wsEndpoint: `ws://127.0.0.1:${PORT}` });
      await existing.pageStack();
      return existing;
    } catch (e) {
      throw new Error(`automation port ${PORT} is occupied by something that isn't a live DevTools instance: ${e.message}`);
    }
  }
  const child = spawn(CLI, ['auto', '--project', PROJECT, '--auto-port', String(PORT), '--trust-project'], {
    detached: true,
    stdio: 'ignore',
  });
  child.unref();
  const portDeadline = Date.now() + 30000;
  while (Date.now() < portDeadline) {
    if (!(await isPortFree(PORT))) break;
    await wait(500);
  }
  if (await isPortFree(PORT)) throw new Error('DevTools auto port did not start within 30 seconds');
  const mp = await getAutomator().connect({ wsEndpoint: `ws://127.0.0.1:${PORT}` });
  // 冷启动(刚彻底 quit 过 DevTools)时项目要整包重编译，30s 不够 —— 2026-08-08 实测
  // 连着报 "logic layer did not become responsive"，其实只是还在编译。
  const aliveDeadline = Date.now() + Number(process.env.WX_ALIVE_TIMEOUT || 150000);
  while (Date.now() < aliveDeadline) {
    try {
      await mp.pageStack();
      writeProjectLease(projectLease);
      return mp;
    } catch (_) {
      await wait(1000);
    }
  }
  throw new Error('DevTools connected but the Mini Program logic layer did not become responsive');
}

// --- frame inspection -------------------------------------------------------
// The old contract only asked "does a non-empty file exist?".  That accepted
// 48-merchantinfo-legacy-tabs.png, which captured the DevTools window (device
// dropdown, refresh/stop icons, URL bar) with the simulator inset on the left,
// and still reported PASS.  Dimensions alone cannot catch it: that frame is a
// perfectly valid 624x1352.  So decode the PNG and look at the pixels.
// ponytail: minimal 8-bit non-interlaced RGB/RGBA reader; DevTools only ever
// emits that form. Reach for a real decoder if the bridge changes format.
// PNG 解码搬到 scripts/lib/png.js:截图判据(uiaudit/verify-batch)也要按像素比图,
// 而它在 CI 里跑 --selftest 时不该继承本模块「必须有 UI_AUDIT_OUT + execSync git」的前提。
const { readPng } = require('./lib/png.js');

// The WeChat simulator draws a device bezel: the outermost pixel ring is the
// page/bezel colour and the frame never contains a tall flat mid-grey column.
// DevTools chrome does exactly that on whichever side the window pads out.
// Returns the failing reason, or null when the frame looks like a clean capture.
function frameDefect(file, expected) {
  const img = readPng(file);
  if (expected && (img.width !== expected.width || img.height !== expected.height)) {
    return `unexpected frame size ${img.width}x${img.height}, expected ${expected.width}x${expected.height}`;
  }
  const isChromeGrey = ([r, g, b]) => {
    const max = Math.max(r, g, b); const min = Math.min(r, g, b);
    return max - min <= 6 && min >= 40 && max <= 90; // flat mid-grey, not page black
  };
  for (const [label, x] of [['right', img.width - 3], ['left', 2]]) {
    let grey = 0;
    const step = 4;
    let sampled = 0;
    for (let y = 2; y < img.height - 2; y += step) {
      sampled += 1;
      if (isChromeGrey(img.px(x, y))) grey += 1;
    }
    // 0.85: real page content can graze the range (03-official-detail-ready's
    // dark hero + grey cards reach 61%); DevTools chrome fills the column.
    if (grey / sampled > 0.85) {
      return `${label} edge column is ${Math.round((grey / sampled) * 100)}% flat DevTools-chrome grey; the simulator is inset, this is a window capture not a page capture`;
    }
  }
  return null;
}

module.exports = {
  fs, net, path, zlib, PROJECT, CLI, PORT, OUT_DIR, ONLY_SHOTS,
  // getter:保持 infra.automator 的调用方式不变,但只有真取用时才加载
  get automator() { return getAutomator(); },
  wait, manifest, writeManifest, isPortFree, launchFixed, readPng, frameDefect,
};
