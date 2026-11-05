// UI purchase of the solo pass: theme → 查看详情与报名 → 随时开玩 → 去结算 → 补全姓名和手机号 → 参与人信息 → (rig: navigator tag
// has no url handler, so 新增 is opened with navigateTo, same url) → fill → 保存 → back → checkout → tick consent → 立即支付 → pay seam
import { run, S } from './lib.mjs';
const [topic, who, pre, name, phone] = process.argv.slice(2);
await run({ path: 'pages/topic/index/index?id=' + topic, identity: who, agree: true }, async (w) => {
  await S(2500);
  await w.tap('查看详情与报名'); await S(1200); await w.tap('随时开玩'); await S(1200); await w.tap('去结算'); await S(2500);
  const t0 = await w.say('checkout');
  if (t0.includes('补全姓名和手机号')) {
    await w.tap('补全姓名和手机号'); await S(2500); await w.say('list');
    if (!(await w.text()).includes(name)) {
      await w.open('pages/addressinfo/addressinfo'); await S(2000);
      await w.ty('输入姓名', name); await w.ty('输入手机号', phone); await w.shot(pre + '-participant-filled');
      await w.tap('保存参与人信息'); await S(2500); await w.say('after-save'); await w.shot(pre + '-participant-saved');
      console.log('ROUTENOW', JSON.stringify(await w.route()));
    }
    const r = await w.route(); if (r[r.length - 1] === 'pages/address/address') { await w.tap(name); await S(2000); }
    console.log('ROUTENOW', JSON.stringify(await w.route()));
  }
  await w.shot(pre + '-checkout-filled'); await w.say('checkout2');
  await w.tap('立即支付'); await S(2000); await w.say('pay-noconsent'); await w.shot(pre + '-pay-noconsent');
  try { await w.tap('同意将姓名、手机号提供给主办方，用于报名核验'); } catch (e) { console.log('MISS consent', e.message); }
  await S(800); await w.shot(pre + '-consent');
  await w.tap('立即支付'); await S(3000); await w.say('paysheet');
  try { await w.pay('success'); } catch (e) { console.log('MISS pay', e.message); }
  await S(5000); await w.say('after-pay'); await w.shot(pre + '-paid');
  console.log('ROUTENOW', JSON.stringify(await w.route()));
});
