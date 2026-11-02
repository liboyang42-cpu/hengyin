import { S, txt, ty } from './lib.mjs';
export default async function (w) {
  await S(4500); await w.scroll(600); await S(600);
  await txt(w,'manage'); 
  await w.tap('场次工具', {nth:0}); await S(1500); await w.shot('79-lead-session-tools'); await txt(w,'tools');
  await w.tap('11月2日 14:00'); await S(1500); await w.shot('80-lead-session-tools-list'); await txt(w,'tl');
  await w.tap('通知本场成员'); await S(3500); console.log(JSON.stringify(await w.route())); await w.shot('81-lead-notify'); await txt(w,'notify');
}
