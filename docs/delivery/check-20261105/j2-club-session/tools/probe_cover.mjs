import { run, S } from './lib.mjs';
await run({ path: 'pages/club/detail/index?id=990004', identity: 'j2b-lead', agree: true }, async (w) => {
  await S(3000);
  console.log('COVER', JSON.stringify(await w.eval(`(function(){var d=getCurrentPages().slice(-1)[0].data;return {cover:d.club&&d.club.cover, logo:d.club&&d.club.logo}})()`)));
  console.log('IMG', JSON.stringify(await w.page.evaluate(() => { const out=[]; const walk=(r)=>{ r.querySelectorAll('*').forEach(e=>{ if(e.shadowRoot) walk(e.shadowRoot); if(e.tagName==='IMG') out.push([e.className, (e.src||'').slice(0,80), e.naturalWidth]); }); }; walk(document); return out.slice(0,8); })));
});
