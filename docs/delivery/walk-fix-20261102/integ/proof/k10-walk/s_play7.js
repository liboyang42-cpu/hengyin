const A = async () => { const a = await h.audio(); return { st: a.state.map(s => [s.id, s.src.slice(0, 8), s.paused ? 'paused' : 'PLAY', s.volume, s.t]), log: a.log.map(l => [l.id, l.ev, l.at, l.v, (l.src || '').slice(0, 8)].filter(x => x != null && x !== '')) }; };
const clr = () => w.page.evaluate(() => { window.MPWEB.audioLog.length = 0; });
const out = {};
await clr();
await h.tap("继续探索").catch(() => {}); await sleep(2500);
await w.loc(31.2342, 121.4782); await sleep(3000);
out.t0 = (await h.say()).slice(-200);
await h.tap("开始任务"); await sleep(4000);
await w.shot("k10-r15-tunnel"); out.a0 = await A(); out.t1 = (await h.say()).slice(-200); await clr();
// interruption test while narration + nothing else
await w.page.evaluate(() => window.MPWEB.interrupt('begin')); await sleep(800); out.intrBegin = await A(); await clr();
await w.page.evaluate(() => window.MPWEB.interrupt('end')); await sleep(800); out.intrEnd = await A(); await clr();
return out;
