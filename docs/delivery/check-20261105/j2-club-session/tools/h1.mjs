import { run, S } from './lib.mjs';
// H1: member 1 opens order 74 › 叫上队友 › 2 人队伍; reads the invite code; member 2 opens the join link and joins
let code = null;
await run({ path: 'subpackageMember/orderinfo/orderinfo?id=74', identity: 'j2b-m1', agree: true }, async (w) => {
  await S(3500); await w.scroll(600); await S(500); await w.say('order'); await w.shot('82-member-order-team-block');
  await w.tap('叫上队友'); await S(2500); await w.say('size'); await w.shot('83-member-team-size');
  await w.tap('2 人队伍'); await S(500); await w.tap('确定', { exact: true }); await S(3500);
  console.log('ROUTE3', JSON.stringify(await w.route())); await w.say('created'); await w.shot('84-member-team-created');
  const d = await w.data(); code = (d.team && (d.team.inviteCode || d.team.code)) || d.inviteCode || d.code || null;
  console.log('CODE', code, JSON.stringify(Object.keys(d)).slice(0, 300));
});
if (!code) { const { execSync } = await import('node:child_process'); code = execSync(`${process.env.HOME}/walk-rig/venv/bin/python db.py "SELECT invite_code FROM play_team ORDER BY id DESC LIMIT 1"`).toString().trim().split('\n').pop(); console.log('CODE(db)', code); }
await run({ path: 'pages/team/join/index?code=' + code, identity: 'j2b-m2', agree: true }, async (w) => {
  await S(3000); await w.say('join'); await w.shot('85-member2-team-join');
  await w.tap('接受邀请并加入'); await S(9000);
  console.log('ROUTE4', JSON.stringify(await w.route())); await w.say('joined'); await w.shot('86-member2-team-joined');
});
