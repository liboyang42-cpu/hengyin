const { launch } = await import(process.env.RIG_WALK);
const w = await launch({ path: 'pages/play/index?preview=1', identity: 'e2e-openid-9004', settle: 2500, agree: true });
console.log(await w.eval(`(()=>{const p=getCurrentPages();return JSON.stringify(p.map(x=>x.route))})()`));
console.log(w.errors().slice(0,5), w.console().slice(0,10));
await w.browser.close();
