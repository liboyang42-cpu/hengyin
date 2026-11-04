import { run, S } from './lib.mjs';
const n = process.argv[2] || 'b3';
await run({ path: 'pages/play/index?topicId=990015', identity: 'e2e-openid-9004', agree: true, lat: 31.2380, lng: 121.4900 }, async (w) => {
  await S(5000);
  for (const t of ['开始游戏', '继续游戏', '继续']) { try { await w.tap(t, { exact: true }); break; } catch (e) {} }
  await S(6000); await w.say('a');
  try { await w.tap('出发', { exact: true }); } catch (e) { console.log('no 出发'); }
  for (let i = 0; i < 4; i++) { await S(3500); await w.shot(`${n}-${i}`); await w.say('i' + i); }
  const d = await w.data(''); console.log('NEXT', JSON.stringify(d.nextNode || '').slice(0, 300));
});
