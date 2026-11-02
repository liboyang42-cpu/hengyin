import { S, txt } from './lib.mjs';
export default async function (w) {
  await S(4500); await w.scroll(250); await S(500);
  await w.tapSel('[aria-label="展开日期清单"]').catch(e => console.log(e.message)); await S(2500);
  await w.shot('144-lead-history'); await txt(w,'hist');
  await w.tap('编辑未来场次', {nth:0}); await S(2500); await w.shot('145-lead-rerun-edit'); await txt(w,'edit');
}
