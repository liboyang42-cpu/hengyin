const A = async () => { const a = await h.audio(); return { st: a.state.map(s => [s.id, s.src.slice(0, 8), s.paused ? 'paused' : 'PLAY', s.volume, s.t]), log: a.log.map(l => [l.id, l.ev, l.at, l.v, (l.src || '').slice(0, 8)].filter(x => x != null && x !== '')) }; };
const clr = () => w.page.evaluate(() => { window.MPWEB.audioLog.length = 0; });
const out = {};
await clr();
await w.scan("01ae720e1d2947d7a9ae4b947fe2429b");
await w.page.mouse.click(195, 583); await sleep(3000);
await w.shot("k10-r12-scan"); out.t0 = (await h.say()).slice(-300); out.a0 = await A(); out.r = await w.route();
const n = await w.net(); out.net = n.filter(x => /checkin|scan|arrive|advanced\/action/.test(x.url)).slice(-3).map(x => [x.url.slice(0, 50), x.status, String(x.reqBody).slice(0, 80), String(x.resBody).slice(0, 160)]);
return out;
