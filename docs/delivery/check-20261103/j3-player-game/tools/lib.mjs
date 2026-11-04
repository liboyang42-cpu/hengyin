// j2 walk helpers: wraps the rig driver; shots at 390x844 @2x, JPEG under 200 KB.
import fs from 'node:fs';
import path from 'node:path';
process.env.TZ = 'Asia/Shanghai'; // phones in China run at +08:00; the container is UTC
import { launch as rigLaunch } from '/root/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs';
export const SHOTS = '/home/user/j3/docs/delivery/check-20261103/j3-player-game/shots';
export const S = (ms) => new Promise((r) => setTimeout(r, ms));
export async function launch(opts) {
  const w = await rigLaunch(opts);
  await w.page.setViewport({ width: 390, height: 844, deviceScaleFactor: 2, isMobile: false, hasTouch: false });
  await S(500);
  w.shot = async (name) => {
    fs.mkdirSync(SHOTS, { recursive: true });
    const file = path.join(SHOTS, name + '.jpg');
    for (const q of [70, 60, 50, 40, 30]) {
      const buf = await w.page.screenshot({ type: 'jpeg', quality: q, clip: { x: 0, y: 0, width: 390, height: 844 } });
      if (buf.length < 200000 || q === 30) { fs.writeFileSync(file, buf); break; }
    }
    console.log('SHOT', name);
    return file;
  };
  w.say = async (label) => { const t = await w.text(); console.log('TEXT[' + label + ']', t.slice(0, 3000)); return t; };
  w.ty = async (ph, text) => {
    const h = await w.page.$(`pierce/input[placeholder="${ph}"]`) || await w.page.$(`pierce/textarea[placeholder="${ph}"]`);
    if (!h) throw new Error('no input ' + ph);
    await h.click({ clickCount: 3 }); await h.type(String(text), { delay: 10 }); await S(300);
  };
  w.report = async () => {
    console.log('ROUTE', JSON.stringify(await w.route()));
    console.log('ERRORS', JSON.stringify(w.errors()));
    const c = w.console().filter((x) => x.startsWith('[error]') && !/home-banners|favicon/.test(x));
    console.log('CONSOLE_ERR', JSON.stringify(c.slice(-15)));
    const n = (await w.net()).filter((x) => /__api/.test(x.url));
    for (const x of n.slice(-30)) { let c = '', m = ''; try { const b = JSON.parse(x.resBody); c = b.code; m = b.msg; } catch {} console.log('NET', x.method, x.url.replace('/__api', ''), x.status, c, m, (x.reqBody || '').slice(0, 160)); }
  };
  return w;
}
export async function run(opts, fn) {
  const w = await launch(opts);
  try { await fn(w); } catch (e) { console.log('STEP_ERROR', e.message); try { await w.shot('_err-' + Date.now()); } catch {} }
  await w.report(); await w.close();
}
