import { run, S } from './lib.mjs';
await run({ path: 'pages/topic/index/index?id=990014&via=session', identity: 'j2b-m3', agree: true }, async (w) => {
  await S(3500); for (let i = 0; i < 3; i++) { await w.scroll(700); await S(400); }
  await w.eval(`(()=>{const p=getCurrentPages().slice(-1)[0]; const o=p.onHeroCta; window.__hits=[]; p.onHeroCta=function(){window.__hits.push('onHeroCta'); return o.apply(this, arguments);}; return 1;})()`);
  await w.page.mouse.click(299, 769); await S(1500);
  console.log('HITS', JSON.stringify(await w.eval('window.__hits')), JSON.stringify(await w.route()));
  console.log('LOG', JSON.stringify(w.log().slice(-15)));
});
