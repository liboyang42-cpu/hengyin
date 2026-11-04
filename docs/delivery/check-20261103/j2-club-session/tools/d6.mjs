import * as fs from 'node:fs';
import { run, S } from './lib.mjs';
// D6: merchant fills the station application (location, note, photo, dates, time) and submits
await run({ path: 'pages/merchant/coop-center/index', identity: 'e2e-openid-9002', agree: true }, async (w) => {
  await S(3500); await w.tap('人民广场 · 喷泉报时'); await w.waitText('申请承接', 15000);
  await w.tap('申请承接'); await S(1500); await w.tap('报名', { exact: true }); await S(2500);
  await w.eval(`window.MPWEB.locQueue = [{name:'人民广场音乐喷泉', address:'上海市黄浦区人民大道', lat:31.2317, lng:121.4752}]`);
  await w.tap('承接地点'); await S(1500); console.log('ADDR', JSON.stringify(await w.data('address')), JSON.stringify(await w.route()));
  await w.ty('填写可用空间、接待安全条件、拍摄许可、现场可配置物料', '喷泉南侧咖啡外摆区，可容纳 6 人，凭票送一杯手冲');
  await w.scroll(300); await w.tap('上传现场照片'); await S(2500); console.log('R', JSON.stringify(await w.route())); try { await w.tap('选取'); } catch (e) { console.log('nocrop'); } await S(4000); console.log('PIC', JSON.stringify(await w.data('picUrl')).slice(0,120));
  await w.say('filled'); await w.shot('55-merchant-apply-filled');
  await w.scroll(600); await S(300);
  await w.tapSel('#field-date-range'); await S(1200); await w.shot('56-merchant-apply-dates'); await w.tap('4', { exact: true }); await w.tap('30', { exact: true }); await S(500); const t = await w.text(); const m = t.match(/确定|完成|确认/); if (m) await w.tap(m[0], { exact: true }); await S(800); await w.tap('点击选择时段'); await S(1000); await w.say('timepop'); await w.shot('56b-merchant-apply-time'); await w.tap('完成', { exact: true }); await S(800); await w.say('after-dates'); await w.shot('57-merchant-apply-ready'); await w.tap('提交报名'); await S(3000); const nn = (await w.net()).filter(x => /merchant\/create/.test(x.url)).pop(); if (nn) fs.writeFileSync('merchant-create-body.json', nn.reqBody); await w.say('submitted'); await w.shot('58-merchant-apply-submitted');
});
