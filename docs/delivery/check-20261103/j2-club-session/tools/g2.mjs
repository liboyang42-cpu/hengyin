import { run, S } from './lib.mjs';
import { call } from './f2.mjs';
// G2: member m2 opens the theme page (the ticket/order page has no way into the session) → hero CTA → play page gather
await run({ path: 'pages/topic/index/index?id=990014', identity: 'j2-m2', agree: true, lat: 31.2306, lng: 121.4739 }, async (w) => {
  await S(3500); console.log('HERO', JSON.stringify(await w.data('heroCta')));
  await w.scroll(800); await S(500); await w.shot('89-member-topic-joined');
  await call(w, 'onHeroCta', '{}'); await S(4000); console.log('ROUTE2', JSON.stringify(await w.route()));
  await w.say('play'); await w.shot('90-member-gather');
});
