#!/usr/bin/env node
// 转台渲染:一个 GLB → 24 帧 300×396 WebP(施工文档第三版 §2.1 / §4.4)。
//
// 用法:  node render.js <glb 路径> <输出目录>
// 约定:  成功 = 输出目录里正好 00.webp … 23.webp,且退出码 0。其余一律视为失败,
//         调用方(objectCardGenTask)不许半截采用。
// 退出码:1 参数或输入不对 / 2 超时 / 3 渲染出错
//
// 页面只加载本机资源:three.js 走本地 node_modules,模型由本进程的回环 HTTP 服务给出,
// 任何发往别处的请求都被拦掉 —— 生成服务给的 GLB 算半可信输入,不让它把浏览器带去外网。
// 渲染参数与 vault 里的视觉样例(渲染参考_baker.html)一致,改一处要两边对齐。

const fs = require('fs');
const http = require('http');
const path = require('path');
const puppeteer = require('puppeteer-core');

const FRAMES = 24;
const WIDTH = 300;
const HEIGHT = 396;          // 卡面 132% 比例
const QUALITY = 72;
const TIMEOUT_MS = 60000;
const MAX_GLB_BYTES = 30 * 1024 * 1024;
const CHROME = process.env.TURNTABLE_CHROME
  || path.join(__dirname, 'chrome-headless-shell-linux64', 'chrome-headless-shell');
const THREE_DIR = path.join(__dirname, 'node_modules', 'three');

const PAGE = `<!doctype html><html><head><meta charset="utf-8">
<style>html,body{margin:0;background:#0d0d0d}canvas{display:block}</style>
<script type="importmap">{"imports":{"three":"/three/build/three.module.js","three/addons/":"/three/examples/jsm/"}}</script>
</head><body><script type="module">
import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { RoomEnvironment } from 'three/addons/environments/RoomEnvironment.js';
const W = ${WIDTH}, H = ${HEIGHT};
const renderer = new THREE.WebGLRenderer({ antialias: true, preserveDrawingBuffer: true });
renderer.setPixelRatio(1); renderer.setSize(W, H);
renderer.toneMapping = THREE.ACESFilmicToneMapping; renderer.outputColorSpace = THREE.SRGBColorSpace;
document.body.appendChild(renderer.domElement);
const bg = document.createElement('canvas'); bg.width = 256; bg.height = 338;
const g = bg.getContext('2d'); const rg = g.createRadialGradient(128, 130, 10, 128, 150, 230);
rg.addColorStop(0, '#2a2a2a'); rg.addColorStop(1, '#0d0d0d'); g.fillStyle = rg; g.fillRect(0, 0, 256, 338);
const bgTex = new THREE.CanvasTexture(bg); bgTex.colorSpace = THREE.SRGBColorSpace;
const scene = new THREE.Scene(); scene.background = bgTex;
scene.environment = new THREE.PMREMGenerator(renderer).fromScene(new RoomEnvironment(), 0.04).texture;
const light = new THREE.DirectionalLight(0xffffff, 1.4); light.position.set(2, 3, 2); scene.add(light);
const cam = new THREE.PerspectiveCamera(30, W / H, 0.01, 100);
const el = 15 * Math.PI / 180, dist = 2.9;
cam.position.set(0, Math.sin(el) * dist, Math.cos(el) * dist); cam.lookAt(0, -0.14, 0);
const pivot = new THREE.Group(); scene.add(pivot);
try {
  const gltf = await new GLTFLoader().loadAsync('/model.glb');
  const obj = gltf.scene; const box = new THREE.Box3().setFromObject(obj);
  const size = box.getSize(new THREE.Vector3()), c = box.getCenter(new THREE.Vector3());
  const s = 1 / Math.max(size.x, size.y, size.z);   // 归一化:最长边 = 1,中心落到原点
  obj.scale.setScalar(s); obj.position.set(-c.x * s, -c.y * s, -c.z * s); pivot.add(obj);
  window.renderFrame = (i) => { pivot.rotation.y = (i / ${FRAMES}) * Math.PI * 2; renderer.render(scene, cam); };
  window.ready = 'ok';
} catch (e) { window.ready = 'error:' + (e && e.message || e); }
</script></body></html>`;

function fail(code, message) {
  process.stderr.write('[turntable] ' + message + '\n');
  process.exit(code);
}

/**
 * GLB 12 字节文件头:魔数 'glTF' + 版本 + 声明总长度。返回不合格的原因,合格返回 null。
 * ★ 声明长度必须等于实际大小:2026-09-22 在 ECS 实测,一个下载断在 2MB/8MB 的 GLB
 *   文件头照样是 glTF,three.js 也能读出一部分几何体,渲染「成功」出 24 帧残缺模型。
 *   只看魔数就会把半截文件标成 READY。
 */
function checkGlbHeader(head, actualSize) {
  if (!head || head.length < 12) return '文件太短,不是 GLB';
  if (head.toString('latin1', 0, 4) !== 'glTF') return '不是 GLB(文件头不是 glTF)';
  if (head.readUInt32LE(4) !== 2) return 'GLB 版本不是 2';
  const declared = head.readUInt32LE(8);
  if (declared !== actualSize) return 'GLB 不完整:头里声明 ' + declared + ' 字节,实际 ' + actualSize;
  return null;
}

function readHead(file) {
  const fd = fs.openSync(file, 'r');
  const head = Buffer.alloc(12);
  const n = fs.readSync(fd, head, 0, 12, 0);
  fs.closeSync(fd);
  return head.subarray(0, n);
}

/** node render.js --selftest:文件头校验的最小自检,不起浏览器、不碰网络。 */
function selftest() {
  const assert = require('assert');
  const head = (magic, version, length) => {
    const b = Buffer.alloc(12); b.write(magic, 0, 'latin1'); b.writeUInt32LE(version, 4); b.writeUInt32LE(length, 8); return b;
  };
  assert.strictEqual(checkGlbHeader(head('glTF', 2, 1000), 1000), null, '完整的 GLB 要放行');
  assert.match(checkGlbHeader(head('glTF', 2, 8110040), 2110044), /不完整/, '截断文件必须拒');
  assert.match(checkGlbHeader(head('JFIF', 2, 1000), 1000), /不是 GLB/);
  assert.match(checkGlbHeader(head('glTF', 1, 1000), 1000), /版本/);
  assert.match(checkGlbHeader(Buffer.from('glTF'), 4), /太短/);
  process.stdout.write('turntable selftest OK\n');
}

// 回环服务只认三类路径:页面、three.js、这一个模型。其余一律 404。
function serve(glbPath) {
  const types = { '.js': 'text/javascript', '.html': 'text/html' };
  const server = http.createServer((req, res) => {
    const url = decodeURIComponent((req.url || '').split('?')[0]);
    if (url === '/' || url === '/page.html') { res.writeHead(200, { 'Content-Type': 'text/html' }); return res.end(PAGE); }
    if (url === '/model.glb') { res.writeHead(200, { 'Content-Type': 'model/gltf-binary' }); return fs.createReadStream(glbPath).pipe(res); }
    if (url.startsWith('/three/')) {
      const file = path.normalize(path.join(THREE_DIR, url.slice('/three/'.length)));
      if (file.startsWith(THREE_DIR + path.sep) && fs.existsSync(file)) {
        res.writeHead(200, { 'Content-Type': types[path.extname(file)] || 'application/octet-stream' });
        return fs.createReadStream(file).pipe(res);
      }
    }
    res.writeHead(404); res.end();
  });
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(server)));
}

async function main() {
  if (process.argv[2] === '--selftest') return selftest();
  const [glbArg, outArg] = process.argv.slice(2);
  if (!glbArg || !outArg) fail(1, '用法: node render.js <glb> <outdir>');
  const glb = path.resolve(glbArg);
  const out = path.resolve(outArg);
  if (!fs.existsSync(glb)) fail(1, '模型不存在: ' + glb);
  if (fs.statSync(glb).size > MAX_GLB_BYTES) fail(1, '模型超过 30MB');
  const bad = checkGlbHeader(readHead(glb), fs.statSync(glb).size);
  if (bad) fail(1, bad);
  fs.mkdirSync(out, { recursive: true });

  const server = await serve(glb);
  const origin = 'http://127.0.0.1:' + server.address().port;
  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'shell',
    // 无显卡:WebGL 走 SwiftShader 软渲染
    args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist',
      ...(process.env.TURNTABLE_NO_SANDBOX === '1' ? ['--no-sandbox'] : [])],
  });
  const killer = setTimeout(() => { browser.close().catch(() => {}); server.close(); fail(2, '超时 ' + TIMEOUT_MS + 'ms'); }, TIMEOUT_MS);
  try {
    const page = await browser.newPage();
    await page.setRequestInterception(true);
    page.on('request', (r) => (r.url().startsWith(origin + '/') ? r.continue() : r.abort()));
    await page.setViewport({ width: WIDTH, height: HEIGHT, deviceScaleFactor: 1 });
    await page.goto(origin + '/page.html');
    await page.waitForFunction('window.ready', { timeout: TIMEOUT_MS });
    const state = await page.evaluate('window.ready');
    if (state !== 'ok') throw new Error('模型加载失败: ' + state);
    for (let i = 0; i < FRAMES; i++) {
      await page.evaluate((n) => window.renderFrame(n), i);
      await page.screenshot({ path: path.join(out, String(i).padStart(2, '0') + '.webp'), type: 'webp', quality: QUALITY });
    }
  } catch (e) {
    clearTimeout(killer); await browser.close().catch(() => {}); server.close();
    fail(3, e && e.message ? e.message : String(e));
  }
  clearTimeout(killer); await browser.close(); server.close();
  const made = fs.readdirSync(out).filter((f) => /^\d{2}\.webp$/.test(f));
  if (made.length !== FRAMES) fail(3, '帧数不对: ' + made.length);
}

main();
