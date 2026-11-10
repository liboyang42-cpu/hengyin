// node play.mjs <opts-json> <steps-json> : launch + run steps; steps: tap/tryTap/tapSel/wait/shot/eval/dump/scroll/loc
const { launch } = await import('/root/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs');
const opts = JSON.parse(process.argv[2]); const steps = JSON.parse(process.argv[3] || '[]');
const w = await launch(Object.assign({ settle: 3000 }, opts));
for (const s of steps) {
  try {
    if (s.shot) { await w.shot(s.shot); console.log('shot', s.shot); }
    else if (s.tap) { await w.tap(...[].concat(s.tap)); console.log('tap', JSON.stringify(s.tap)); }
    else if (s.tryTap) { try { await w.tap(...[].concat(s.tryTap)); console.log('tap', s.tryTap); } catch (e) { console.log('skip', s.tryTap); } }
    else if (s.tapSel) { await w.tapSel(s.tapSel); console.log('tapSel', s.tapSel); }
    else if (s.wait) await new Promise(r => setTimeout(r, s.wait));
    else if (s.eval) console.log('EVAL', JSON.stringify(await w.eval(s.eval)).slice(0, 1500));
    else if (s.scroll != null) await w.scroll(s.scroll);
    else if (s.loc) await w.loc(s.loc[0], s.loc[1]);
    else if (s.open) { await w.open(s.open); console.log('open', s.open); }
    else if (s.type) { await w.type(s.type[0], s.type[1]); console.log('type', s.type[1]); }
    else if (s.dump) console.log('TEXT', (await w.text()).slice(0, 1500));
  } catch (e) { console.log('FAIL', JSON.stringify(s).slice(0, 80), e.message.slice(0, 200)); }
}
console.log('ERR', JSON.stringify(w.errors().slice(0, 5)));
console.log('NET', JSON.stringify((await w.net()).filter(n => /help|arriv|play\/(advanced|node|run)/.test(n.url)).map(n => [n.method, n.url.slice(0,120), n.status, (n.resBody||'').slice(0,300)])));
await w.browser.close();
