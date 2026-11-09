const A = async () => { const a = await h.audio(); return { st: a.state.map(s => [s.id, s.src.slice(0, 8), s.paused ? 'paused' : 'PLAY', s.volume, s.t]), log: a.log.map(l => [l.id, l.ev, l.at, l.v, (l.src || '').slice(0, 8)].filter(x => x != null && x !== '')) }; };
const clr = () => w.page.evaluate(() => { window.MPWEB.audioLog.length = 0; });
const out = {};
await clr();
await h.tap("开始任务"); await sleep(4000);
await w.shot("k10-r09-bridge-task"); out.task = await A(); out.t0 = (await h.say()).slice(-250); await clr();
return out;
