import { run, S } from './lib.mjs';
// F7: m3 opens the shared invite link (pages/team/join?code=...) and joins
const code = process.argv[2], who = process.argv[3] || 'j2-m3', shoot = who === 'j2-m3';
await run({ path: 'pages/team/join/index?code=' + code, identity: who, agree: true }, async (w) => {
  await S(3000); await w.say('join'); if (shoot) await w.shot('83-member3-team-join');
  await w.tap('接受邀请并加入'); await S(9000);
  console.log('ROUTE2', JSON.stringify(await w.route())); await w.say('joined'); if (shoot) await w.shot('84-member3-team-joined');
});
