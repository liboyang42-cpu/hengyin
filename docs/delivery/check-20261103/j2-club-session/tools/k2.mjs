import { run, S } from './lib.mjs';
await run({ path: 'pages/club/settlement/index?clubId=990004', identity: 'j2-m1', agree: true }, async (w) => {
  await S(3500); await w.say('new-settle'); await w.shot('154-new-owner-settlement-after-selfplay');
});
await run({ path: 'pages/club/edit/index?id=7001', identity: 'e2e-openid-9003', agree: true }, async (w) => {
  await S(3500); await w.say('edit7001'); await w.shot('155-lead-9003-club-edit-7001');
});
