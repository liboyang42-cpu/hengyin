import { S, txt, ty } from './lib.mjs';
export default async function (w) {
  await S(4000);
  await w.page.evaluate(() => getCurrentPages().slice(-1)[0].bmClick({}));
  await S(2000); await w.shot('53-member-fe-session-picker');
  await w.tap('11.2'); await S(1500); await txt(w,'picked'); await w.shot('54-member-fe-ticket');
  try { await w.tap('标准场次票'); await S(800); } catch (e) { console.log('no ticket name'); }
  await w.tap('下一步 · 去结算'); await S(3500); console.log(JSON.stringify(await w.route()));
  await w.shot('55-member-baoming'); await txt(w,'baoming');
}
