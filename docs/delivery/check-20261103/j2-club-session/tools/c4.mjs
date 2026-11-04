import { run, S } from './lib.mjs';
// C4: lead sees the theme waiting for review on 管理, then opens it
const tag = process.argv[2] || 'pending';
await run({ path: 'pages/club/detail/index?id=990004', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(2500); await w.tap('管理', { exact: true }); await S(1500);
  await w.say('manage-' + tag); await w.shot(tag === 'pending' ? '32-manage-theme-pending' : '35-manage-theme-approved');
  await w.scroll(600); await w.say('manage2-' + tag); await w.shot(tag === 'pending' ? '33-manage-theme-pending-2' : '36-manage-theme-approved-2');
  await w.tap('人民广场 · 喷泉报时'); await S(2500); await w.say('topic-' + tag); await w.shot(tag === 'pending' ? '34-club-topic-pending' : '37-club-topic-approved');
});
