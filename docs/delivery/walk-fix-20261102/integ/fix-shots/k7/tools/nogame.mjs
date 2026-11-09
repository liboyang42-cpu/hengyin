// k7-map rig walk of k2 j1 N1: a session ticket with no game (no theme, no template) shows its QR and no 开始游戏,
// on 订单详情 and in 票夹. Player 9009 also holds a session-13 ticket that has a game, for contrast.
// Fixture: activity 20 is a copy of session 10 with topic_id NULL (direct test-DB write); sign-up and payment are real.
import { launch } from '/root/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs';
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
let w = await launch({ path: 'subpackageMember/orderinfo/orderinfo?id=79', identity: 'e2e-openid-9009', agree: true, settle: 5000 });
await w.shot('k2-nogame-order-detail');
const t1 = await w.text(); console.log('order79', t1.slice(0, 500).replace(/\n/g, ' / ')); console.log('has 开始游戏:', /开始游戏/.test(t1));
await w.close();
w = await launch({ path: 'subpackageMember/orderinfo/orderinfo?id=74', identity: 'e2e-openid-9009', agree: true, settle: 5000 });
const t2 = await w.text(); console.log('order74 (has game) has 开始游戏:', /开始游戏/.test(t2));
await w.shot('k2-game-order-detail');
await w.close();
w = await launch({ path: 'subpackageMember/signup/index', identity: 'e2e-openid-9009', agree: true, settle: 5000 });
await w.shot('k2-ticket-wallet-1');
console.log('wallet', (await w.text()).slice(0, 400).replace(/\n/g, ' / '));
for (let i = 0; i < 3; i++) {
  const d = await w.eval(`(()=>{const p=getCurrentPages().slice(-1)[0];const k=Object.keys(p.data).filter(x=>/idx|index|current|flip|qr/i.test(x));const o={};k.forEach(x=>o[x]=typeof p.data[x]==='object'?JSON.stringify(p.data[x]).slice(0,80):p.data[x]);return JSON.stringify(o)})()`);
  console.log('deck', d);
  try { await w.eval(`(()=>{const p=getCurrentPages().slice(-1)[0];const fn=p.onDeckChange||p.onSwiperChange||p.onCardChange;if(fn)fn.call(p,{detail:{current:${i + 1},source:'touch'}});return !!fn})()`); } catch (e) {}
  await sleep(2500); await w.shot('k2-ticket-wallet-' + (i + 2));
  console.log('wallet', i + 2, (await w.text()).slice(0, 300).replace(/\n/g, ' / '));
}
console.log('net', JSON.stringify((await w.net()).filter(x => /qr|code|entry|ticket/i.test(x.url)).map(x => x.status + ' ' + x.url.replace(/.*\/api/, '/api'))));
console.log('errors', JSON.stringify(await w.errors()));
await w.close();
