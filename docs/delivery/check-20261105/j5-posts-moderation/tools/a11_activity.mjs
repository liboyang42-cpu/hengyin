// Merchant publishes an activity through the real form (营销 → 发活动 → pages/publish/activity?scope=MERCHANT).
// VENUE: POI name returned by the rig's chooseLocation seam (stands in for the WeChat map picker).
// TICKET: ticket name typed by the merchant on step 3. Rig only; not a phone.
import { start, sleep } from './lib.mjs';
import fs from 'node:fs';
const IM = JSON.parse(fs.readFileSync('/tmp/claude-0/j5-images.json', 'utf8'));
const E = process.env;
const N = E.N || 'x', VENUE = E.VENUE || '江边咖啡角', TICKET = E.TICKET || '体验票', TITLE = E.TITLE || 'j5 周末咖啡品鉴', DESC = E.DESC || '一起尝三款手冲咖啡';
const w = await start({ path: 'pages/merchant/marketing/index', identity: E.WHO || 'e2e-openid-9002' });
await sleep(1500);
if (E.ENTRY) await w.snap(`${N}-0-marketing`);
// tap the LAST visible element whose own text equals t (sheet footers render after the page body)
const tapLast = async (t) => {
  const pt = await w.page.evaluate((t) => {
    const out = []; const walk = (root) => { for (const el of root.querySelectorAll('*')) { if (el.shadowRoot) walk(el.shadowRoot); const own = [...el.childNodes].filter((n) => n.nodeType === 3).map((n) => n.textContent).join('').trim(); if (own === t) { const r = el.getBoundingClientRect(); if (r.width > 0 && r.height > 0 && r.bottom > 0 && r.top < 844) out.push({ x: r.x + r.width / 2, y: r.y + r.height / 2 }); } } };
    walk(document); return out[out.length - 1] || null; }, t);
  if (!pt) throw new Error('tapLast: none ' + t); await w.page.mouse.click(pt.x, pt.y); await sleep(900);
};
w.tapLast = tapLast;
await w.step('营销 → 发活动', () => w.tap('发活动', { exact: true })); await sleep(2500);
await w.page.evaluate((v) => { wx.chooseLocation = function (a) { setTimeout(() => { const r = { name: v, address: '上海市黄浦区中山东一路 1 号', latitude: 31.2304, longitude: 121.4737, errMsg: 'chooseLocation:ok' }; a.success && a.success(r); a.complete && a.complete(r); }, 20); }; }, VENUE);
await w.step('标题', () => w.typeInto('input[placeholder="活动标题"]', TITLE));
await w.step('地点', async () => { const b = await (await w.page.$('pierce/[aria-label="选择活动地点"]')).boundingBox(); await w.page.mouse.click(b.x + 4, b.y + 4); }); await sleep(1000);
await w.step('日期', () => w.tapSel('.picker_group')); await sleep(1800);
await w.step('开始 → 下一步', () => tapLast('下一步')); await sleep(1200);
await w.step('结束 → 完成', () => tapLast('完成')); await sleep(1500);
await w.step('类型', () => w.tap('请选择活动类型')); await sleep(1500);
const t1 = await w.text(); console.log('type sheet', t1.slice(0, 600));
if (E.CAT) { await w.step('选类型 ' + E.CAT, () => tapLast(E.CAT)); await sleep(600); await w.step('类型 确定', () => tapLast(E.CATOK || '确定')); await sleep(1200); }
await w.snap(`${N}-1-basic`);
await w.step('下一步 1', () => tapLast('下一步')); await sleep(1500);
console.log('step2', (await w.text()).slice(0, 700));
await w.step('说明', () => w.typeInto('textarea[placeholder="活动说明"]', DESC));
await w.photo(IM.clean);
{ await w.step('封面', () => w.tapAt('.upload__add')); await sleep(1500); if ((await w.text()).includes('选取')) await w.step('选取', () => w.tap('选取', { exact: true })); await sleep(2500); w.lastBody(/upload/); }
await w.snap(`${N}-2-content`);
await w.step('下一步 2', () => tapLast('下一步')); await sleep(1500);
console.log('step3', (await w.text()).slice(0, 700));
await w.step('票单名称', () => w.typeInto('input[placeholder="请输入票单名称"]', TICKET));
await w.step('价格', () => w.typeInto('input[placeholder="请输入价格"]', E.PRICE || '0'));
await w.step('库存', () => w.typeInto('input[placeholder="请输入库存"]', '10'));
await w.snap(`${N}-3-ticket`);
await w.step('发布', () => tapLast(E.SUBMIT || '发布')); await sleep(3000);
w.lastBody(/activity\/publish/);
console.log('after', (await w.text()).slice(0, 500));
await w.snap(`${N}-4-after`);
await sleep(2500); await w.snap(`${N}-5-after-late`);
console.log('late', (await w.text()).slice(0, 400));
await w.finish();
