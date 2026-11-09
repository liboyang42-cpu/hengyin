import { go, sleep } from './go.mjs';
const [code, tag, id, entry] = process.argv.slice(2);
await go(tag, { path: 'pages/merchant/index/index', identity: id || 'e2e-openid-9002' }, async (w, shot) => {
  await sleep(1800);
  if (entry === 'workbench') { await w.tap('E2E 探店日一期'); await sleep(2500); }
  await w.scan(code); await w.tap('扫码'); await sleep(2500); await shot(tag + '-result');
  await sleep(1500); await shot(tag + '-result2');
});
