import { run, S } from './lib.mjs';
// M3: club owner 林一帆: theme console of 人民广场 · 慢逛一下午 → 招商 layer → approve the two chapter applications
await run({ path: 'pages/topic/merchantinfo/merchantinfo?topicId=990015&scope=CLUB', identity: 'j2b-m1', agree: true }, async (w) => {
  await S(4000); await w.say('console'); await w.shot('177-fe-owner-console-reopened');
  await w.tap('商家', { exact: true }); await S(3000); await w.say('layer'); await w.shot('178-fe-owner-layer-reopened');
  for (let i = 0; i < 1; i++) {
    const t = await w.text(); const m = ['确认']; console.log('BTN', m && m[0]); if (!m) break;
    await w.tap(m[0], { exact: true }); await S(1500); await w.say('dlg' + i); await w.shot('179-fe-owner-approve2-' + i);
    await w.tap('确认通过', { exact: true });
    await S(3000); await w.say('after' + i);
  }
  await w.shot('180-fe-owner-after-approve2');
});
