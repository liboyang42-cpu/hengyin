import { S, txt, ty } from './lib.mjs';
export default async function (w) {
  await S(4500); await w.tap('帖子', {exact:true}); await S(2500);
  await w.tap('有什么新鲜好玩的分享吗？'); await S(2500); await w.shot('91-lead-post-compose'); await txt(w,'compose');
  console.log(JSON.stringify(await w.route()));
  const ta = await w.page.$('pierce/textarea'); if (ta) { await ta.click(); await ta.type('今天下午两场：13:50 武康路四店（已满，候补 1 位），14:00 外滩源电报（社员场）。集合点见各自报名页，迟到的直接跟第一章。', {delay:5}); }
  await S(500); await w.tap('设为公告'); await S(400); await w.shot('92-lead-post-filled'); await w.tap('发布', {exact:true}); await S(3500); await w.shot('93-lead-post-published'); await txt(w,'pub');
}
