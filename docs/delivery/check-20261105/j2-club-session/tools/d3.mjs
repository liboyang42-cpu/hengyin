import { run, S } from './lib.mjs';
// D3: merchant fills the station application including dates and time, then 提交报名
await run({ path: 'pages/merchant/coop-center/index', identity: 'e2e-openid-9002', agree: true }, async (w) => {
  await S(3500); await w.tap('人民广场 · 喷泉报时'); await S(5000); await w.scroll(700); await S(500);
  await w.tap('申请承接'); await S(1500); await w.tap('报名', { exact: true }); await S(2500);
  await w.eval(`window.MPWEB.locQueue = [{name:'人民广场音乐喷泉', address:'上海市黄浦区人民大道', lat:31.2317, lng:121.4752}]`);
  await w.tap('承接地点'); await S(1500);
  await w.ty('填写可用空间、接待安全条件、拍摄许可、现场可配置物料', '喷泉南侧咖啡外摆区，可容纳 6 人，凭票送一杯手冲');
  await w.scroll(300); await w.tap('上传现场照片'); await S(2500); try { await w.tap('选取'); } catch (e) { console.log('nocrop'); } await S(4000);
  await w.scroll(600); await S(300);
  await w.tap('点击选择日期区间'); await S(1500); await w.say('datepop'); await w.shot('34-merchant-apply-dates');
  await w.tap('5', { exact: true }); await w.tap('30', { exact: true }); await S(500);
  const t = await w.text(); const m = t.match(/确定|完成|确认/); if (m) await w.tap(m[0], { exact: true }); await S(800);
  await w.tap('点击选择时段'); await S(1000); await w.say('timepop'); await w.shot('35-merchant-apply-time'); await w.tap('完成', { exact: true }); await S(800);
  await w.say('ready'); await w.shot('36-merchant-apply-ready');
  await w.tap('提交报名'); await S(3000); await w.say('submitted'); await w.shot('37-merchant-apply-submitted');
});
