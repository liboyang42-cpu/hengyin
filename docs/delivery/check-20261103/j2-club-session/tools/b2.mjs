import { run, S } from './lib.mjs';
// B2: players open the club and join
const who = process.argv[2] || 'j2-m1'; const shoot = who === 'j2-m1';
await run({ path: 'pages/club/detail/index?id=990004', identity: who, agree: true }, async (w) => {
  await S(2500); if (shoot) { await w.say('guest-view'); await w.shot('20-member-club-before-join'); }
  const t = await w.text(); const m = t.match(/加入俱乐部|申请加入|加入/); console.log('JOINBTN', m && m[0]);
  await w.tap('加入', { exact: true }); await S(2000);
  if (shoot) { await w.say('after-join'); await w.shot('21-member-join-welcome'); }
});
