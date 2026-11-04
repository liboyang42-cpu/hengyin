import { start, sleep } from './lib.mjs';
const N = process.env.N || 'x';
const w = await start({ path: 'subpackageB/pages/im/list/index', identity: process.env.WHO || 'e2e-openid-9009' });
await sleep(1000); await w.dump(); await w.snap(`${N}-1-inbox`);
await w.step('发件箱', () => w.tap('发件箱', { exact: true })); await sleep(1200); await w.dump(); await w.snap(`${N}-2-outbox`);
await w.step('收件箱', () => w.tap('收件箱', { exact: true })); await sleep(1000);
await w.step('小助手', () => w.tap(process.env.SYS || '城瘾小助手')); await sleep(2000); await w.dump(); await w.snap(`${N}-3-assistant`);
console.log('route', JSON.stringify(await w.route()));
await w.back(); await sleep(1000);
if (process.env.PEER) { await w.step('会话', () => w.tap(process.env.PEER)); await sleep(2000); await w.dump(); await w.snap(`${N}-4-thread`); }
await w.finish();
