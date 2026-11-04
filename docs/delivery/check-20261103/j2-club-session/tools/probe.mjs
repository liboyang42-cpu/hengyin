import { run, S } from './lib.mjs';
await run({ path: 'pages/club/topic-detail/index?topicId=990014&clubId=990004', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(3000); await w.tap('商家', { exact: true }); await S(3500);
  console.log('KEYS', JSON.stringify(await w.eval(`(()=>{const d=getCurrentPages().slice(-1)[0].data;const o={};for(const k in d){if(/show|open|layer|sheet|visible/i.test(k))o[k]=typeof d[k]==='object'?Object.keys(d[k]||{}).slice(0,8):d[k];}return o;})()`)));
  console.log('OPTS', JSON.stringify(await w.eval(`getCurrentPages().slice(-1)[0].options`)));
});
