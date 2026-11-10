// qx-u3 rig driver: real mini-program page in the walkthrough rig, frame sequence of one motion.
//
//   RIG=<rig dir> node frames.mjs <scenario.json>
//
// scenario: { name, opts (walk.mjs launch opts), open?, setup: [walk steps], action: step, frames: N, windowMs,
//             latency?: { match, ms },  stub?: { match, body } }
//  · latency: rig-only network delay for one endpoint (real request still goes to the real backend).
//  · stub:    rig-only fixed response for one endpoint, used ONLY where the fixture cannot produce the server fact
//             (reported per shot in RESULT.md as HARNESS). Never used for a read that drives a screen.
// Frames come from CDP Page.startScreencast (compositor frames), so CSS transitions are captured at real speed.
// Output: $RIG_SHOTS/<name>-f<k>.jpg (390 wide, q70) and <name>-strip.jpg (frames side by side, 195 px each).
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';

const RIG = process.env.RIG;
const { launch } = await import(path.join(RIG, 'mpweb/walk.mjs'));
const sc = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const OUT = process.env.RIG_SHOTS || path.join(process.cwd(), 'shots');
fs.mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const w = await launch(sc.opts || {});
const page = w.page;
if (sc.latency || sc.stub) {
  // The runtime fetches /__api/* from the same origin; intercept only the named endpoint.
  await page.setRequestInterception(true);
  page.on('request', async (req) => {
    const url = req.url();
    if (sc.stub && url.includes(sc.stub.match)) {
      await sleep(sc.stub.ms || 0);
      return req.respond({ status: 200, contentType: 'application/json', body: JSON.stringify(sc.stub.body) });
    }
    if (sc.latency && url.includes(sc.latency.match)) await sleep(sc.latency.ms);
    req.continue();
  });
}
async function run(step) {
  const [k, v] = Object.entries(step)[0];
  if (k === 'wait') return sleep(v);
  if (k === 'open') return w.open(v);
  if (k === 'tap') return Array.isArray(v) ? w.tap(v[0], v[1]) : w.tap(v);
  if (k === 'tapSel') return w.tapSel(v);
  if (k === 'eval') return w.eval(v);
  if (k === 'shot') return w.shot(v);
  if (k === 'scroll') return w.scroll(v);
  if (k === 'dump') { console.log(JSON.stringify(String(await w.text()).slice(0, 1500))); return; }
  // r4-motion additions: tryTap (skip when absent), type [selector, text], loc [lat, lng]
  if (k === 'tryTap') { try { await (Array.isArray(v) ? w.tap(v[0], v[1]) : w.tap(v)); console.log('tap', JSON.stringify(v)); } catch (e) { console.log('skip', JSON.stringify(v)); } return; }
  if (k === 'type') return w.type(v[0], v[1]);
  if (k === 'loc') return w.loc(v[0], v[1]);
  throw new Error('unknown step ' + k);
}
if (sc.open) await w.open(sc.open);
for (const s of sc.setup || []) await run(s);

const cdp = await page.target().createCDPSession();
// r4-motion: frame-drop proxy = compositor frames captured per second over the window, plus Performance metrics delta
await cdp.send('Performance.enable');
const metricsBefore = (await cdp.send('Performance.getMetrics')).metrics.reduce((o, m) => (o[m.name] = m.value, o), {});
const frames = [];
cdp.on('Page.screencastFrame', async (f) => {
  frames.push({ t: f.metadata.timestamp, data: f.data });
  try { await cdp.send('Page.screencastFrameAck', { sessionId: f.sessionId }); } catch (e) { /* closing */ }
});
await cdp.send('Page.startScreencast', { format: 'jpeg', quality: 80, maxWidth: 390, maxHeight: 844, everyNthFrame: 1 });
await sleep(150);
const t0 = Date.now() / 1000;
await run(sc.action);
await sleep(sc.windowMs || 2000);
await cdp.send('Page.stopScreencast');
const metricsAfter = (await cdp.send('Performance.getMetrics')).metrics.reduce((o, m) => (o[m.name] = m.value, o), {});
const after = frames.filter((f) => f.t >= t0 - 0.05);
const span = after.length ? after.at(-1).t - after[0].t : 0;
console.log('frames captured', after.length, 'span', span.toFixed(2) + 's');
{
  // gaps > 50 ms between consecutive compositor frames while the page is still animating = dropped frames (proxy, headless rig)
  let gaps = 0, maxGap = 0;
  for (let i = 1; i < after.length; i++) { const g = (after[i].t - after[i - 1].t) * 1000; if (g > 50) gaps++; if (g > maxGap) maxGap = g; }
  const delta = {};
  for (const k of ['LayoutCount', 'RecalcStyleCount', 'LayoutDuration', 'RecalcStyleDuration', 'ScriptDuration', 'TaskDuration']) delta[k] = +(metricsAfter[k] - metricsBefore[k]).toFixed(4);
  const report = { name: sc.name, framesCaptured: after.length, spanMs: Math.round(span * 1000), fpsAvg: span > 0 ? +(after.length / span).toFixed(1) : null, gapsOver50ms: gaps, maxGapMs: Math.round(maxGap), metricsDelta: delta };
  fs.writeFileSync(path.join(OUT, `${sc.name}-metrics.json`), JSON.stringify(report, null, 2));
  console.log('metrics', JSON.stringify(report));
}
// pick N frames at the requested offsets (ms after the action) or evenly spread
const N = sc.frames || 8;
const picks = [];
const offsets = sc.offsets || Array.from({ length: N }, (_, i) => Math.round((sc.windowMs || 2000) * i / (N - 1)));
for (const ms of offsets) {
  const target = t0 + ms / 1000;
  let best = after[0];
  for (const f of after) if (Math.abs(f.t - target) < Math.abs(best.t - target)) best = f;
  picks.push({ ms, f: best });
}
const files = [];
if (!after.length) {
  console.log('NO FRAMES');
  if (sc.final) await run(sc.final);
  console.log(JSON.stringify((await w.net()).slice(-4).map((n) => [n.method, n.url, n.status, String(n.resBody).slice(0, 200)])));
  await w.browser.close(); process.exit(2);
}
picks.forEach(({ ms, f }, i) => {
  const file = path.join(OUT, `${sc.name}-f${i + 1}.jpg`);
  fs.writeFileSync(file + '.raw.jpg', Buffer.from(f.data, 'base64'));
  // normalise to 390 wide q70 and stamp the offset
  execFileSync('convert', [file + '.raw.jpg', '-resize', '390x', '-quality', '70', file]);
  fs.unlinkSync(file + '.raw.jpg');
  files.push({ file, ms: Math.round((f.t - t0) * 1000) });
});
const tiles = files.map(({ file, ms }) => ['(', file, '-resize', '195x', '-gravity', 'south', '-background', '#111', '-fill', '#ddd',
  '-splice', '0x22', '-pointsize', '14', '-annotate', '+0+3', `+${ms} ms`, ')']).flat();
execFileSync('convert', [...tiles, '+append', '-quality', '70', path.join(OUT, `${sc.name}-strip.jpg`)]);
if (sc.final) await run(sc.final);
console.log(JSON.stringify({ errors: w.errors().slice(0, 5), net: (await w.net()).slice(-8).map((n) => [n.method, n.url, n.status, String(n.resBody).slice(0, 160)]) }));
await w.browser.close();
