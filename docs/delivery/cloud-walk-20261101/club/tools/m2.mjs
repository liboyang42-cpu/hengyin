import { S, ty, txt } from './lib.mjs';
export default async function (w) {
  await S(4000);
  await w.tap('加入', {exact:true}); await S(2500); await w.shot('31-member-welcome'); await w.tap('去看活动'); await S(3500);
  await w.shot('33-member-events'); await txt(w,'acts');
  await w.tap('外滩源 · 迟到百年的电报'); await S(4000);
  await w.shot('34-member-topic'); await txt(w,'topic'); console.log(JSON.stringify(await w.route()));
  await w.scroll(700); await S(600); await w.shot('35-member-topic-2'); await txt(w,'topic2');
  await w.scroll(900); await S(600); await w.shot('36-member-topic-3'); await txt(w,'topic3');
}
