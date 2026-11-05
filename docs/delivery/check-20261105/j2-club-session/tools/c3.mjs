import { run, S } from './lib.mjs';
// C3: lead's 管理 tab and the theme's club card at a given stage (arg = shot prefix)
const tag = process.argv[2] || '23-manage-after-publish';
await run({ path: 'pages/club/detail/index?id=990004', identity: 'j2b-lead', agree: true }, async (w) => {
  await S(2500); await w.tap('管理', { exact: true }); await S(2000); await w.say('manage'); await w.shot(tag);
  await w.scroll(600); await S(500); await w.say('manage2'); await w.shot(tag + '-b');
});
