import { S, txt } from './lib.mjs';
export default async function (w) {
  await S(5000); await w.shot('101-lead-fe-running'); await txt(w,'td');
  await w.tap('报名参与者'); await S(3000); await w.shot('102-lead-fe-customers-running'); await txt(w,'cust');
  await w.page.evaluate(() => getCurrentPages().slice(-1)[0].goLedger && getCurrentPages().slice(-1)[0].goLedger()); await S(3500);
  console.log(JSON.stringify(await w.route())); await w.shot('103-lead-fe-ledger'); await txt(w,'ledger');
}
