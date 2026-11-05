import { go, sleep } from './go.mjs';
await go('i01-f5', { path: 'pages/merchant/index/index', identity: 'e2e-openid-9002' }, async (w, shot) => {
  await sleep(2500); await shot('i01-home-chapter-mine-500'); await w.scroll(400); await shot('i02-home-chapter-mine-500-scrolled');
  console.log('DATA', JSON.stringify({ joinError: await w.data('joinError'), hostError: await w.data('hostError'), cards: (await w.data('projectCards')).length }));
});
