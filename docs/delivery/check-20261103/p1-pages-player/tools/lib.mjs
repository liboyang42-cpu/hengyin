// p3 walk helper: wraps the rig's walk.mjs launch, shoots at 390x844 @2x, JPEG < 200 KB.
import fs from 'node:fs';
import path from 'node:path';
const RIG = process.env.RIG_DIR || (process.env.HOME + '/rig/docs/delivery/cloud-walk-20261101/rig');
const { launch } = await import(RIG + '/mpweb/walk.mjs');
export const SHOTS = path.resolve(new URL('..', import.meta.url).pathname, 'shots');
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
export async function open(opts) {
  const w = await launch({ agree: true, settle: 2500, ...opts });
  await w.page.setViewport({ width: 390, height: 844, deviceScaleFactor: 2 });
  await sleep(400);
  w.shot2 = async (name) => {
    fs.mkdirSync(SHOTS, { recursive: true });
    const file = path.join(SHOTS, name + '.jpg');
    let q = 70, buf;
    for (;;) { buf = await w.page.screenshot({ type: 'jpeg', quality: q, clip: { x: 0, y: 0, width: 390, height: 844 } }); if (buf.length < 200 * 1024 || q <= 30) break; q -= 8; }
    fs.writeFileSync(file, buf); return name;
  };
  w.report = async () => {
    const errs = w.errors(); const cons = w.console().filter((c) => /^\[error/.test(c) && !/home-banners|favicon/.test(c));
    const net = (await w.net()).filter((n) => n.status >= 400 || (n.body && n.body.code && n.body.code !== 200));
    return { route: await w.route(), pageErrors: errs.map((e) => e.split('\n').slice(0, 2).join(' / ')), consoleErrors: cons.slice(0, 12), badNet: net.map((n) => `${n.status} ${n.method} ${n.url.slice(0, 120)}`) };
  };
  w.safeTap = async (t, o) => { try { await w.tap(t, o); return true; } catch (e) { return false; } };
  return w;
}
