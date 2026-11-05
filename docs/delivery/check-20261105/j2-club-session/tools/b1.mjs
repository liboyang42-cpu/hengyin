import { run, S } from './lib.mjs';
// B1: players open the club and join (first one is photographed)
const who = process.argv[2] || 'j2b-m1'; const club = process.argv[3]; const shoot = who === 'j2b-m1';
await run({ path: 'pages/club/detail/index?id=' + club, identity: who, agree: true }, async (w) => {
  await S(2500); if (shoot) { await w.say('before-join'); await w.shot('12-member-club-before-join'); }
  await w.tap('加入', { exact: true }); await S(2000);
  if (shoot) { await w.say('after-join'); await w.shot('13-member-join-welcome'); }
});
