import { S, txt, ty } from './lib.mjs';
export default async function (w) {
  await S(4500);
  await ty(w, '例如：周六集合提醒', '14:00 外滩源集合提醒');
  await ty(w, '写清集合时间、地点与需要准备的物品', '14:00 在外滩源壹号正门台阶集合，我穿黄色马甲。手机充好电，带一支笔。迟到的直接打开小程序跟上第一章。');
  await w.scroll(600); await S(500); await w.tap('预览受众'); await S(2500); await w.shot('82-lead-notify-preview'); await txt(w,'preview');
  await w.tapSel('.send-btn'); await S(2000); try { await w.modal('confirm'); } catch (e) {} await S(4000);
  await w.shot('83-lead-notify-sent'); await txt(w,'sent');
}
