// Player 9009 writes a theme 评价 from the theme page (评价 card → sheet 评价路线 → 发布评价).
import { start, sleep } from './lib.mjs';
const E = process.env, N = E.N || 'x';
const w = await start({ path: `pages/topic/index/index?id=${E.TID || 990015}`, identity: E.WHO || 'e2e-openid-9009' });
await sleep(4000);
await w.scroll(844); await sleep(1500);
await w.step('评价 card', async () => { try { await w.tapAt('[aria-label="成为第一个评价的人"]'); } catch (e) { await w.tapAt('[aria-label="写评价"]'); } }); await sleep(1500);
// RIG workaround: the mouse tap lands on the inner <text> and the card's bindtap never fires in the rig (first pass saw the same).
// A click event dispatched on the card element itself runs the page's real voteClick.
if (!(await w.page.evaluate(() => getCurrentPages().slice(-1)[0].data.voteShow))) {
  const h = (await w.page.$('pierce/[aria-label="成为第一个评价的人"]')) || (await w.page.$('pierce/[aria-label="写评价"]'));
  await h.evaluate((el) => el.dispatchEvent(new MouseEvent('click', { bubbles: true, composed: true }))); await sleep(1500);
}
console.log('voteShow', await w.page.evaluate(() => getCurrentPages().slice(-1)[0].data.voteShow));
await w.step('5 星', () => w.tapAt('.image-item-hit', 4)); await sleep(500);
if (Number(await w.page.evaluate(() => getCurrentPages().slice(-1)[0].data.answer)) !== 4) { // same rig tap gap as the card
  const hs = await w.page.$$('pierce/.image-item-hit'); await hs[4].evaluate((el) => el.dispatchEvent(new MouseEvent('click', { bubbles: true, composed: true }))); await sleep(500); console.log('star via dispatched click');
}
await w.step('输入', () => w.typeInto('textarea[placeholder="请输入您的评价内容..."]', E.TEXT)); await sleep(500);
await w.snap(`${N}-a-typed`);
console.log('canSubmit', await w.page.evaluate(() => getCurrentPages().slice(-1)[0].data.canSubmitComment));
await w.step('发布评价', () => w.tap('发布评价', { exact: true })); await sleep(800);
if (!w.bodies.some((b) => /comment\/add/.test(b.u))) { // rig tap gap again: dispatch the click on the sheet's cy-btn
  const bs = await w.page.$$('pierce/cy-sheet cy-btn'); for (const b of bs) { const t = await b.evaluate((el) => el.innerText || el.textContent); if (/发布评价/.test(t)) { await b.evaluate((el) => el.dispatchEvent(new MouseEvent('click', { bubbles: true, composed: true }))); console.log('submit via dispatched click'); break; } }
  await sleep(1200);
} await w.snap(`${N}-b0-toast`); await sleep(1700);
w.lastBody(/comment\/add/);
console.log('after', (await w.text()).slice(0, 300));
await w.snap(`${N}-b-after`);
await w.finish();
