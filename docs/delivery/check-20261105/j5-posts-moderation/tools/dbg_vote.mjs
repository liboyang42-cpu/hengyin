import { start, sleep } from './lib.mjs';
const w = await start({ path: 'pages/topic/index/index?id=990015', identity: 'e2e-openid-9009' });
await sleep(4000); await w.scroll(844); await sleep(1500);
const info = await w.page.evaluate(() => { const out = []; const walk = (r) => { for (const el of r.querySelectorAll('*')) { if (el.shadowRoot) walk(el.shadowRoot); if ((el.getAttribute && el.getAttribute('aria-label')) === '成为第一个评价的人') { const b = el.getBoundingClientRect(); const top = document.elementFromPoint(b.x + b.width / 2, b.y + b.height / 2); out.push({ b: [b.x, b.y, b.width, b.height], tag: el.tagName, cls: el.className, top: top && (top.tagName + '.' + top.className) }); } } }; walk(document); return out; });
console.log(JSON.stringify(info));
await w.page.evaluate(() => { const p = getCurrentPages().slice(-1)[0]; window.__vc = 0; const f = p.voteClick; p.voteClick = function () { window.__vc++; return f.apply(this, arguments); }; });
await w.page.mouse.click(195, 664); await sleep(1500); await w.shot("dbg-vote");
console.log('vc', await w.page.evaluate(() => window.__vc), 'voteShow', await w.page.evaluate(() => getCurrentPages().slice(-1)[0].data.voteShow));
await w.finish();
