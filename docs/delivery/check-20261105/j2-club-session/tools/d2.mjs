import { run, S } from './lib.mjs';
import fs from 'node:fs';
// D2: merchant 9002 sets the shop location, then 合作中心 › theme › 申请承接 › 报名: fills the station application and submits
if (process.env.SKIP_DECOR) {} else await run({ path: 'pages/merchant/decor/index', identity: 'e2e-openid-9002', agree: true }, async (w) => {
  await S(3000);
  await w.eval(`window.MPWEB.locQueue = [{name:'探店日样板咖啡', address:'上海市黄浦区人民大道120号', lat:31.2312, lng:121.4745}]`);
  await w.tap('门店信息'); await S(1500); try { await w.tap('编辑', { exact: true }); await S(800); } catch (e) { console.log('no 编辑', e.message); }
  await w.tap('店铺地址'); await S(2500); try { await w.tap('保存', { exact: true }); await S(2000); } catch (e) { console.log('no 保存', e.message); }
  await w.say('saved'); await w.shot('27-merchant-shop-location');
});
await run({ path: 'pages/merchant/coop-center/index', identity: 'e2e-openid-9002', agree: true }, async (w) => {
  await S(3500); await w.say('coop'); await w.shot('28-merchant-coop-center');
  await w.tap('人民广场 · 喷泉报时'); await S(5000); await w.say('theme'); await w.shot('29-merchant-theme-page');
  await w.scroll(700); await S(800); await w.say('theme2'); await w.shot('29b-merchant-theme-page-scrolled');
  await w.tap('申请承接'); await S(1500); await w.say('apply-sheet'); await w.shot('30-merchant-apply-sheet');
  await w.tap('报名', { exact: true }); await S(2500); await w.say('form'); await w.shot('31-merchant-apply-form');
  await w.eval(`window.MPWEB.locQueue = [{name:'人民广场音乐喷泉', address:'上海市黄浦区人民大道', lat:31.2317, lng:121.4752}]`);
  await w.tap('承接地点'); await S(1500);
  await w.ty('填写可用空间、接待安全条件、拍摄许可、现场可配置物料', '喷泉南侧咖啡外摆区，可容纳 6 人，凭票送一杯手冲');
  await w.scroll(300); await w.tap('上传现场照片'); await S(2500); try { await w.tap('选取'); } catch (e) { console.log('nocrop'); } await S(4000);
  await w.say('filled'); await w.shot('32-merchant-apply-filled');
  await w.scroll(600); await S(300); await w.say('lower'); await w.shot('33-merchant-apply-lower');
});
