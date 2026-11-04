// Open one page (via navigateTo from home so query options reach onLoad), shoot it at several times, print text,
// route, toasts/modals, bad responses. Usage: node peek.mjs <identity|guest> <path?query> <shotName> [steps-json]
import { open, sleep } from './lib.mjs';
const [identity, url, name, stepsJson] = process.argv.slice(2);
const w = await open({ path: 'pages/index/index', identity: identity === 'guest' ? undefined : identity });
await w.page.evaluate((u) => { const p = u.split('?')[0]; if (window.MPWEB.tabList.includes(p)) wx.switchTab({ url: '/' + p }); else wx.navigateTo({ url: '/' + u }); }, url);
const sys = () => w.page.evaluate(() => { const m = document.querySelector('.wx-modal'); const t = document.querySelector('.wx-toast'); return (m ? 'MODAL[' + m.innerText.replace(/\s+/g, ' ') + '] ' : '') + (t ? 'TOAST[' + t.innerText.replace(/\s+/g, ' ') + ']' : ''); });
await sleep(700); console.log('t0.7', (await w.route()).join('>'), await sys()); await w.shot2(name + '-0');
await sleep(2500); console.log('t3.2', (await w.route()).join('>'), await sys());
await w.shot2(name);
console.log('TEXT', (await w.text()).slice(0, 1500));
for (const s of JSON.parse(stepsJson || '[]')) {
  const [k, v] = Object.entries(s)[0];
  try {
    if (k === 'tap') await w.tap(v); else if (k === 'tapn') await w.tap(v[0], { nth: v[1] }); else if (k === 'scroll') await w.scroll(v); else if (k === 'wait') await sleep(v); else if (k === 'modal') await w.modal(v); else if (k === 'sheet') await w.sheet(v); else if (k === 'eval') console.log('EVAL', JSON.stringify(await w.eval(v)).slice(0, 1500)); else if (k === 'type') await w.type(v[0], v[1]); else if (k === 'tapSel') await w.tapSel(v);
    else if (k === 'shot') { await w.shot2(v); console.log('SHOT', v, (await w.route()).slice(-1)[0], await sys(), '|', (await w.text()).slice(0, 900)); }
  } catch (e) { console.log('STEPFAIL', k, v, e.message); }
  await sleep(600);
}
const net = await w.net();
console.log('NET', net.filter((n) => !/config\/features|consents/.test(n.url)).map((n) => n.status + ' ' + n.method + ' ' + n.url.replace('/__api', '').slice(0, 90) + (n.body && n.body.code && n.body.code != 200 ? ' code=' + n.body.code + ' ' + (n.body.msg || '') : '')).join('\n    '));
console.log('LOG', (await w.log()).slice(0, 8).join(' ; ').slice(0, 600));
const rep = await w.report(); console.log('ERR', JSON.stringify(rep.pageErrors), JSON.stringify(rep.badNet));
await w.close();
