import { run, S } from './lib.mjs';
// H3: member opens 票夹, taps the ticket → play page → gather → 开启定位 → 我到了 → 定位并签到
const who = process.argv[2] || 'j2b-m1', shoot = !!process.env.SHOOT; const LAT = Number(process.argv[3] || 31.2306), LNG = Number(process.argv[4] || 121.4739); const P = process.env.P || '';
await run({ path: 'subpackageMember/signup/index', identity: who, agree: true, lat: LAT, lng: LNG }, async (w) => {
  await S(3500); await w.say('wallet'); if (shoot) await w.shot(P + '90-member-ticket-wallet');
  await w.tap('开始游戏'); await S(5000); console.log('ROUTE2', JSON.stringify(await w.route()));
  await w.say('play'); if (shoot) await w.shot(P + '91-member-play-intro');
  try { await w.tap('开始游戏', { exact: true }); await S(3000); } catch (e) { console.log('no intro start'); }
  await w.say('gather'); if (shoot) await w.shot(P + '92-member-gather');
  try { await w.tap('开启定位', { nth: 1 }); await S(2500); } catch (e) { try { await w.tap('开启定位'); await S(2500); } catch (e2) { console.log('no 开启定位'); } }
  const tt = await w.text(); if (tt.includes('先用扫码')) { console.log('LOC-SHEET-STAYS'); await w.tap('先用扫码'); await S(1500); }
  try { await w.tap('轻点跳过'); await S(1500); } catch (e) {}
  for (let i = 0; i < 4; i++) { try { await w.tap('我到了'); } catch (e) { console.log('no 我到了'); } await S(2000); if ((await w.text()).includes('定位并签到')) break; try { await w.tap('先用扫码'); } catch (e) {} try { await w.tap('轻点跳过'); } catch (e) {} await S(1500); } await w.say('arrive1'); if (shoot) await w.shot(P + '93-member-arrive-sheet');
  await w.tap('定位并签到'); await S(700); if (shoot) { await w.say('arrive-toast'); await w.shot(P + '94a-member-arrive-toast'); } await S(2800);
  await w.say('arrived'); if (shoot) await w.shot(P + '94-member-arrived');
});
