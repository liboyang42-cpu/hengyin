import { S, txt, ty } from './lib.mjs';
export default async function (w) {
  await S(4500);
  await w.tap('本场未到场'); await S(800);
  await ty(w, '例如：周六集合提醒', '你在哪？我们已出发');
  await ty(w, '写清集合时间、地点与需要准备的物品', '大家已经从外滩源出发去真光大楼了。到了直接打开小程序第一章，我在圆明园路口等你 10 分钟。');
  await w.scroll(600); await S(500); await w.tap('预览受众'); await S(2500); await w.shot('132-lead-notify-noshow'); await txt(w,'preview');
  await w.tapSel('.send-btn'); await S(4000); await w.shot('133-lead-notify-noshow-sent'); await txt(w,'sent');
}
