// Second-pass peek: open one page from home with navigateTo (query reaches onLoad), wait, shoot once (+1 scrolled
// shot if the page is taller than the screen), write logs/<name>.json with text, route, modal/toast, bad responses.
// Usage: node peek2.mjs <identity|guest> <path?query> <name> [settleMs]
import fs from 'node:fs'; import path from 'node:path';
import { open, sleep } from './lib.mjs';
const [identity, url, name, settle] = process.argv.slice(2);
const LOG = path.resolve(new URL('..', import.meta.url).pathname, 'logs'); fs.mkdirSync(LOG, { recursive: true });
const w = await open({ path: 'pages/index/index', identity: identity === 'guest' ? undefined : identity });
await sleep(800);
await w.page.evaluate((u) => { const p = u.split('?')[0]; if (window.MPWEB.tabList.includes(p)) wx.switchTab({ url: '/' + p }); else wx.navigateTo({ url: '/' + u }); }, url);
await sleep(Number(settle || 3500));
const sys = () => w.page.evaluate(() => { const m = document.querySelector('.wx-modal'); const t = document.querySelector('.wx-toast'); const a = document.querySelector('.wx-as'); return (m ? 'MODAL[' + m.innerText.replace(/\s+/g, ' ') + '] ' : '') + (a ? 'SHEET[' + a.innerText.replace(/\s+/g, ' ') + '] ' : '') + (t ? 'TOAST[' + t.innerText.replace(/\s+/g, ' ') + ']' : ''); });
const out = { name, identity, url, route: await w.route(), sys: await sys(), text: await w.text() };
await w.shot2(name);
const t0 = out.text; await w.scroll(700); await sleep(500); const t1 = await w.text();
if (t1 !== t0) { out.textScrolled = t1; await w.shot2(name + '-s'); }
const net = await w.net();
out.net = net.filter((n) => !/config\/features|consents/.test(n.url)).map((n) => n.status + ' ' + n.method + ' ' + n.url.replace('/__api', '').slice(0, 100) + (n.body && n.body.code && n.body.code != 200 ? ' code=' + n.body.code + ' ' + (n.body.msg || '') : ''));
out.report = await w.report();
fs.writeFileSync(path.join(LOG, name + '.json'), JSON.stringify(out, null, 1));
console.log(name, out.route.slice(-1)[0], out.sys, '|', out.text.slice(0, 300).replace(/\n/g, ' '), out.report.pageErrors.length ? 'PAGEERR ' + out.report.pageErrors[0].slice(0, 120) : '');
await w.close();
