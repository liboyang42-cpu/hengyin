import { S } from './lib.mjs';
export default async function (w) {
  await S(3500); await w.tap('加入', {exact:true}); await S(2500); await w.tap('去看活动'); await S(3500);
  console.log(await w.page.evaluate(() => { let o=''; (function walk(r){ for (const e of r.querySelectorAll('.feed')) o+=e.outerHTML.slice(0,2500); for (const e of r.querySelectorAll('*')) if (e.shadowRoot) walk(e.shadowRoot); })(document); return o; }));
}
