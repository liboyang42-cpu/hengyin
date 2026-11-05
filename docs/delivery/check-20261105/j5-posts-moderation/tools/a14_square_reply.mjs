// Player 9009 comments on player 9004's 广场 post (pages/square/detail), pressing the keyboard 发送 (bindconfirm).
import { start, sleep } from './lib.mjs';
const E = process.env, N = E.N || 'x';
const w = await start({ path: `pages/square/detail/index?id=${E.PID}`, identity: E.WHO || 'e2e-openid-9009' });
await sleep(4000);
await w.step('评论', () => w.typeInto('input[placeholder="留下你的评论"]', E.TEXT));
await w.snap(`${N}-a-typed`);
await w.page.keyboard.press('Enter'); await sleep(3500);
w.lastBody(/comment/);
console.log('after', (await w.text()).slice(0, 400));
await w.snap(`${N}-b-after`);
await w.finish();
