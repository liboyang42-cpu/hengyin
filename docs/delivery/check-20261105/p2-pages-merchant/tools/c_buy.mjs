import { go, sleep } from './go.mjs';
const id = process.argv[2] || 'e2e-openid-9009';
const tag = process.argv[3] || 'c';
await go(tag + '-buy', { path: 'pages/topic/index/index', identity: id }, async (w, shot) => {
  await w.relaunch('/pages/topic/index/index?id=990014'); await sleep(2500); await shot(tag + '01-topic');
  console.log((await w.text()).slice(0, 1500));
});
