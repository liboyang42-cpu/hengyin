import { run, S } from './lib.mjs';
// N1: ROUND8 lead: pages/club/edit as club lead 9003 with ?id=7001
await run({ path: 'pages/club/edit/index?id=7001', identity: 'e2e-openid-9003', agree: true }, async (w) => {
  await S(3500); await w.say('edit'); await w.shot('200-lead-9003-club-edit-7001');
});
