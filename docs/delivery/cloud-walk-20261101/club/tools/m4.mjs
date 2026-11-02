import { S, txt, ty } from './lib.mjs';
export default async function (w) {
  await S(4000);
  for (let i = 0; i < 6; i++) { await w.scroll(800); await S(500); }
  await w.shot('52-member-fe-bottom'); await txt(w,'bottom');
  for (const k of ['buyOpts','sessions','activityList','info.lifecycle','tickets']) { try { console.log(k, JSON.stringify(await w.data(k)).slice(0,300)); } catch(e){} }
}
