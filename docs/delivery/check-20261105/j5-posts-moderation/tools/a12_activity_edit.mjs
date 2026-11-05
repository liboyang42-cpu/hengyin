// Merchant edits a published activity (我的项目 → 活动 → card → edit form) and changes the ticket name / venue.
import { start, sleep } from './lib.mjs';
const E = process.env, N = E.N || 'x';
const w = await start({ path: `pages/publish/activity/index?id=${E.AID}&scope=MERCHANT`, identity: E.WHO || 'e2e-openid-9002' });
await sleep(3000);
const tapLast = async (t) => {
  const pt = await w.page.evaluate((t) => { const out = []; const walk = (root) => { for (const el of root.querySelectorAll('*')) { if (el.shadowRoot) walk(el.shadowRoot); const own = [...el.childNodes].filter((n) => n.nodeType === 3).map((n) => n.textContent).join('').trim(); if (own === t) { const r = el.getBoundingClientRect(); if (r.width > 0 && r.height > 0 && r.bottom > 0 && r.top < 844) out.push({ x: r.x + r.width / 2, y: r.y + r.height / 2 }); } } }; walk(document); return out[out.length - 1] || null; }, t);
  if (!pt) throw new Error('tapLast: none ' + t); await w.page.mouse.click(pt.x, pt.y); await sleep(900);
};
console.log('loaded', (await w.text()).slice(0, 300));
if (E.VENUE) {
  await w.step('上一步', () => tapLast('上一步')); await sleep(1200); await w.step('上一步', () => tapLast('上一步')); await sleep(1200);
  await w.page.evaluate((v) => { wx.chooseLocation = function (a) { setTimeout(() => { const r = { name: v, address: '上海市黄浦区中山东一路 1 号', latitude: 31.2304, longitude: 121.4737, errMsg: 'chooseLocation:ok' }; a.success && a.success(r); a.complete && a.complete(r); }, 20); }; }, E.VENUE);
  await w.step('地点', async () => { const b = await (await w.page.$('pierce/[aria-label="选择活动地点"]')).boundingBox(); await w.page.mouse.click(b.x + 4, b.y + 4); }); await sleep(1200);
}
await w.step('下一步 1', () => tapLast('下一步')); await sleep(1500);
await w.step('下一步 2', () => tapLast('下一步')); await sleep(1500);
if (E.TICKET) await w.step('票单名称', () => w.typeInto('input[placeholder="请输入票单名称"]', E.TICKET));
await w.snap(`${N}-1-edited`);
await w.step('保存', () => tapLast('保存')); await sleep(3000);
w.lastBody(/activity\/update/);
console.log('after', (await w.text()).slice(0, 400));
await w.snap(`${N}-2-after`);
await w.finish();
