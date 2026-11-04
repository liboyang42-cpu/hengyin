import { run, S } from './lib.mjs';
await run({ path: 'pages/topic/index/index?id=990015', identity: 'e2e-openid-9004', agree: true }, async (w) => {
  await S(3000);
  await w.tap('查看详情与报名'); await S(1500); await w.tap('随时开玩'); await S(1500); await w.tap('去结算'); await S(2500);
  await w.tap('补全姓名和手机号'); await S(2500);
  await w.tap('昵称'); await S(1200); await w.shot('08-nickname-sheet'); await w.say('nick');
  const h = await w.page.$('pierce/input'); if (h) { await h.click({ clickCount: 3 }); await h.type('林夏', { delay: 20 }); }
  await S(1500); await w.say('nick2'); await w.shot('09-nickname-typed');
  try { await w.tap('确定'); } catch (e) { try { await w.tap('完成'); } catch (e2) { console.log('no confirm'); } }
  await S(1500); await w.tap('保存'); await S(2500); await w.shot('10-profile-saved'); await w.say('saved');
  console.log('ROUTE1', JSON.stringify(await w.route()));
  await w.back(); await S(2500); await w.shot('11-checkout-after-profile'); await w.say('back');
});
