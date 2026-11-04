import { start, sleep } from './lib.mjs';
const N = process.env.N || 'x';
const w = await start({ path: 'pages/userinfo/userinfo?userId=9009', identity: 'e2e-openid-9004' });
await w.snap(`${N}-1-profile`);
await w.step('发消息', () => w.tap('发消息', { exact: true })); await sleep(2500);
console.log('route', JSON.stringify(await w.route())); await w.snap(`${N}-2-chat-empty`);
const inputs = await w.page.evaluate(() => { const o = []; (function walk(r) { for (const e of r.querySelectorAll('*')) { if (e.shadowRoot) walk(e.shadowRoot); if (/^(INPUT|TEXTAREA)$/.test(e.tagName)) o.push(e.tagName + ':' + e.placeholder); } })(document); return o; });
console.log('inputs', JSON.stringify(inputs));
const sel = process.env.SEL || 'input';
let i = 3;
for (const t of JSON.parse(process.env.TEXTS)) {
  await w.step('输入', () => w.typeInto(sel, t));
  await w.page.keyboard.press('Enter'); await sleep(300);
  try { await w.tap('发送', { exact: true }); } catch (e) {}
  await sleep(350);
  console.log('early', (await w.text()).slice(0, 220)); await w.snap(`${N}-${i++}-sent`); w.lastBody(/im\/send/); await sleep(1500);
}
await w.snap(`${N}-${i}-thread`);
await w.finish();
