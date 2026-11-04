import { run, S } from './lib.mjs';
// D0: merchant 9002 (探店日样板咖啡) sets the shop location in 店铺装修 › 店铺地址 (precondition for being found by 招商)
await run({ path: 'pages/merchant/decor/index', identity: 'e2e-openid-9002', agree: true }, async (w) => {
  await S(3000); await w.say('decor'); await w.shot('49-merchant-decor');
  await w.eval(`window.MPWEB.locQueue = [{name:'探店日样板咖啡', address:'上海市黄浦区人民大道120号', lat:31.2312, lng:121.4745}]`);
  await w.tap('门店信息'); await S(1500); await w.say('basic'); try { await w.tap('编辑', { exact: true }); await S(800); } catch (e) { console.log('no 编辑', e.message); }
  await w.tap('店铺地址'); await S(2500); await w.say('after-loc'); await w.shot('50-merchant-decor-location');
  try { await w.tap('保存', { exact: true }); await S(2000); } catch (e) { console.log('no 保存', e.message); }
  await w.say('saved');
});
