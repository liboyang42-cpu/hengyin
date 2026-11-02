import { S, txt } from './lib.mjs';
export default async function (w) {
  await S(6000); try { await w.tap('开始游戏'); } catch (e) {} await S(4000);
  await w.tap('继续探索'); await S(3000); try { await w.tap('继续探索'); await S(3000); } catch (e) {}
  await w.shot('119-member-route-card'); await txt(w,'card');
  await w.tap('开始任务'); await S(4000); await w.shot('120-member-station1-task'); await txt(w,'task');
  const inp = async (v) => { const h = await w.page.$('pierce/input') || await w.page.$('pierce/textarea'); await h.click({clickCount:3}); await h.type(v, {delay:10}); };
  await inp('1900'); await w.tap('提交', {exact:true}); await S(2000); await w.tap('开启定位', {exact:true, nth:1}).catch(()=>w.tap('开启定位', {exact:true})); await S(3000); await w.loc(31.24268,121.48841); await S(2000); await w.tap('提交', {exact:true}); await S(3000); await w.shot('121-member-wrong'); await txt(w,'wrong');
  await w.tap('看提示（1/2）').catch(()=>{}); await S(2500); await w.shot('122-member-hint'); await txt(w,'hint');
  await inp('1873'); await w.tap('提交', {exact:true}); await S(3500); await w.shot('123-member-right'); await txt(w,'right');
}
