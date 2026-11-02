import { S, ty, txt } from './lib.mjs';
export default async function (w) {
  await S(1500); await w.tap('开始创建'); await S(1200);
  await w.tap('旅行组织'); await w.tap('下一步', { exact: true }); await S(900);
  await w.tap('城市定向'); await w.tap('解谜路线'); await w.tap('美食体验'); await w.shot('08-create-direction');
  await w.tap('下一步', { exact: true }); await S(900);
    await ty(w, '给你的俱乐部起个名字', '城瘾跑团');
  await ty(w, '用一句话说清你的俱乐部是做什么的', '每月一次，周末下午走一条上海老街，边走边解谜，走完一起吃饭。');
  await w.shot('09-create-form'); await w.tap('下一步', { exact: true }); await S(1500);
  await txt(w, 'after-dup-name-step3'); await w.shot('10-create-dup-name');
  for (let i = 0; i < 2; i++) { const t = await w.text(); if (t.includes('如 上海')) break; }
  try { await ty(w, '如 上海', '上海'); await ty(w, '逗号分隔，如 徒步，摄影', '城市漫步，解谜，老洋房'); await ty(w, '如 沉浸剧情', '轻松慢走'); } catch (e) { console.log('no step4', e.message); }
  await w.shot('11-create-step4'); await w.tap('创建俱乐部', { exact: true }); await S(2500);
  await txt(w, 'after-create-dup'); await w.shot('12-create-dup-result');
  await w.tap('重试', {exact:true}); await S(2000); await txt(w,'after-retry');
  await w.tapSel('.nav__back'); await S(1000); await txt(w,'after-back'); await w.shot('13-create-back');
  await ty(w, '给你的俱乐部起个名字', '沪上慢走社');
  await w.tap('下一步', { exact: true }); await S(900); await txt(w,'s4again');
  await w.tap('创建俱乐部', { exact: true }); await S(3000);
  await txt(w, 'created'); await w.shot('14-club-created');
}
