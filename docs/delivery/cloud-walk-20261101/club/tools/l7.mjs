import { S, txt } from './lib.mjs';
export default async function (w) {
  await S(4500); await w.shot('58-lead-td-fe'); await txt(w,'td');
  await w.tap('报名参与者'); await S(3000); await w.shot('59-lead-customers'); await txt(w,'cust');
  await w.scroll(600); await S(500); await w.shot('60-lead-customers-2'); await txt(w,'cust2');
}
