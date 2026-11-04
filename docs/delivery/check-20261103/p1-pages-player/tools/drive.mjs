// Free-form driver: node drive.mjs <identity|guest> <path?query> '<steps json>'
// steps: {tap},{tapn:[t,n]},{tapSel},{scroll},{wait},{modal},{sheet},{type:[sel,text]},{shot},{data:'path'},{eval},{dump},{open},{back},{relaunch},{pay},{loc}
import { open, sleep } from './lib.mjs';
const [identity, url, stepsJson] = process.argv.slice(2);
const w = await open({ path: 'pages/index/index', identity: identity === 'guest' ? undefined : identity });
if (url !== 'pages/index/index') await w.page.evaluate((u) => { const p = u.split('?')[0]; if (window.MPWEB.tabList.includes(p)) wx.switchTab({ url: '/' + p }); else wx.navigateTo({ url: '/' + u }); }, url);
await sleep(3000);
const sys = () => w.page.evaluate(() => { const m = document.querySelector('.wx-modal'); const t = document.querySelector('.wx-toast'); const a = document.querySelector('.wx-as'); return (m ? 'MODAL[' + m.innerText.replace(/\s+/g, ' ') + '] ' : '') + (a ? 'SHEET[' + a.innerText.replace(/\s+/g, ' ') + '] ' : '') + (t ? 'TOAST[' + t.innerText.replace(/\s+/g, ' ') + ']' : ''); });
let n0 = 0;
for (const s of JSON.parse(stepsJson || '[]')) {
  const [k, v] = Object.entries(s)[0];
  try {
    if (k === 'tap') await w.tap(v); else if (k === 'tapn') await w.tap(v[0], { nth: v[1] }); else if (k === 'tapSel') await w.tapSel(v);
    else if (k === 'scroll') await w.scroll(v); else if (k === 'wait') await sleep(v); else if (k === 'modal') await w.modal(v); else if (k === 'sheet') await w.sheet(v);
    else if (k === 'type') await w.type(v[0], v[1]); else if (k === 'pay') await w.pay(v); else if (k === 'loc') await w.loc(v);
    else if (k === 'click') { const b = await w.page.evaluate((sel) => { let hit = null; (function walk(r) { for (const el of r.querySelectorAll('*')) { if (hit) return; if (el.shadowRoot) walk(el.shadowRoot); if (el.matches(sel)) { const q = el.getBoundingClientRect(); if (q.width > 1 && q.top >= 0 && q.top < 844) hit = el; } } })(document); if (!hit) return null; const q = hit.getBoundingClientRect(); return { x: q.left + q.width / 2, y: q.top + q.height / 2 }; }, v); if (!b) throw new Error('no element ' + v); await w.page.mouse.click(b.x, b.y); console.log('CLICK', v, Math.round(b.x), Math.round(b.y)); }
    else if (k === 'fill') { const all = [...await w.page.$$('pierce/input'), ...await w.page.$$('pierce/textarea')]; let i = 0; for (const h of all) { if (!(await h.boundingBox())) continue; if (i >= v.length) break; const val = v[i++]; if (val === null) continue; await h.click({ clickCount: 3 }); await h.evaluate((e) => { e.value = ''; }); await h.type(val, { delay: 5 }); } }
    else if (k === 'open') await w.open(v); else if (k === 'back') await w.back(); else if (k === 'relaunch') await w.relaunch(v);
    else if (k === 'data') console.log('DATA', v, JSON.stringify(await w.data(v)).slice(0, 1500));
    else if (k === 'eval') console.log('EVAL', JSON.stringify(await w.eval(v)).slice(0, 2000));
    else if (k === 'dump') console.log('DUMP', (await w.route()).slice(-1)[0], await sys(), '|', (await w.text()).slice(0, 1500));
    else if (k === 'shot') { await w.shot2(v); console.log('SHOT', v, (await w.route()).slice(-1)[0], await sys(), '|', (await w.text()).slice(0, 700)); }
  } catch (e) { console.log('STEPFAIL', k, JSON.stringify(v), e.message.slice(0, 200)); }
  await sleep(400);
  const net = await w.net(); for (const x of net.slice(n0)) if (!/config\/features|consents/.test(x.url)) console.log('  NET', x.status, x.url.replace('/__api', '').slice(0, 80), x.body && x.body.code && x.body.code != 200 ? 'code=' + x.body.code + ' ' + (x.body.msg || '') : ''); n0 = net.length;
}
const rep = await w.report(); console.log('ERR', JSON.stringify(rep.pageErrors), 'LOG', (await w.log()).slice(-6).join(' ; ').slice(0, 500));
await w.close();
