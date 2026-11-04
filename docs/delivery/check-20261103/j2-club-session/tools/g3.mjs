import { run, S } from './lib.mjs';
// G3: member opens 票夹 (subpackageMember/signup), taps the ticket → play page (gather)
const who = process.argv[2] || 'j2-m2', shoot = who === 'j2-m2' || process.argv[5] === 'shoot'; const LAT = Number(process.argv[3] || 31.2306), LNG = Number(process.argv[4] || 121.4739);
await run({ path: 'subpackageMember/signup/index', identity: who, agree: true, lat: LAT, lng: LNG }, async (w) => {
  await S(3500); await w.say('wallet'); if (shoot) await w.shot('90-member-ticket-wallet');
  await w.tap('开始游戏'); await S(5000); console.log('ROUTE2', JSON.stringify(await w.route()));
  await w.say('play'); if (shoot) await w.shot('91-member-play-intro');
  await w.tap('开始游戏', { exact: true }); await S(3000); await w.say('gather'); if (shoot) await w.shot('92-member-gather');
  await w.tap('开启定位', { nth: 1 }); await S(2500); if (shoot) await w.say('after-allow'); const tt = await w.text(); if (tt.includes('先用扫码')) { console.log('LOC-SHEET-STAYS'); await w.tap('先用扫码'); await S(1500); } try { await w.tap('轻点跳过'); await S(1500); } catch (e) {} if (shoot) await w.shot('92b-member-gather-clear'); await w.tap('我到了'); await S(2000); await w.say('arrive1'); if (shoot) await w.shot('93-member-arrive-sheet');
  await w.tap('定位并签到'); await S(3500);
  await w.say('arrived'); if (shoot) await w.shot(who === 'j2-m2' ? '94-member-arrived' : '95-' + who + '-arrive-result');
});
