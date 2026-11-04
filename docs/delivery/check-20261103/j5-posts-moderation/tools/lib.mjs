// j5 journey helpers on top of the rig driver (rig-only; real mini-program code in a browser runtime, not a phone).
import { launch } from '/root/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs';
import fs from 'node:fs';
const SH = process.env.RIG_SHOTS;
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
export async function start(opts) {
  const w = await launch({ agree: true, settle: 2500, ...opts });
  w.bodies = [];
  w.page.on('response', async (r) => { const u = r.url(); if (!u.includes('__api') || r.request().method() === 'OPTIONS') return; try { const b = await r.text(); w.bodies.push({ u: u.replace(/^.*__api/, ''), s: r.status(), b: b.slice(0, 300) }); } catch (e) {} });
  w.lastBody = (re) => { const x = [...w.bodies].reverse().find((y) => re.test(y.u)); console.log('resp', x ? x.s + ' ' + x.u + ' ' + x.b : 'none'); return x; };
  w.step = async (label, fn) => { try { await fn(); console.log('ok  ', label); return true; } catch (e) { console.log('FAIL', label, '-', e.message.slice(0, 200)); return false; } };
  w.dump = async () => { const t = await w.text(); console.log('text:', t.slice(0, 1500)); return t; };
  w.snap = async (name) => { await w.shot(name); console.log('shot', name, (fs.statSync(SH + '/' + name + '.jpg').size / 1024 | 0) + 'KB'); };
  w.toasts = () => w.page.evaluate(() => { const L = document.querySelector('#sysLayer'); return L ? L.innerText.trim().slice(0, 300) : ''; });
  w.typeInto = async (sel, text) => {
    // set value like a user typing into a textarea/input inside shadow roots
    const h = await w.page.$('pierce/' + sel);
    if (!h) throw new Error('no ' + sel);
    await h.click(); await w.page.keyboard.down('Control'); await w.page.keyboard.press('KeyA'); await w.page.keyboard.up('Control'); for (let k = 0; k < 4; k++) await w.page.keyboard.press('Backspace'); for (let k = 0; k < 120; k++) await w.page.keyboard.press('Backspace'); await h.type(String(text), { delay: 10 }); await sleep(400);
  };
  w.photo = async (dataUrl) => w.page.evaluate((d) => { MPWEB.photoForShot = () => d; MPWEB.samplePhoto = () => d; }, dataUrl);
  w.finish = async () => {
    const errs = w.errors(); console.log('--- page errors', errs.length); errs.slice(0, 8).forEach((e) => console.log('  ', e.split('\n')[0].slice(0, 250)));
    const net = (await w.net()).filter((n) => n.status >= 400 || /comment|post|im\/|upload|follow|like|club|merchant|topic/.test(n.url));
    console.log('--- net'); net.slice(-40).forEach((n) => console.log('  ', n.status, n.method, n.url.replace(/^.*__api/, '').slice(0, 120)));
    await w.close();
  };
  return w;
}
