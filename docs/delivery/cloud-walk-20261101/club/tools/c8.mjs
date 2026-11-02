import { S, txt } from './lib.mjs';
export default async function (w) {
  await S(5000); await w.tap('去选一场'); await S(1500);
  await w.page.evaluate(() => getCurrentPages().slice(-1)[0].onSessionPick({ currentTarget: { dataset: { id: 18 } } }));
  await S(5000);
  await w.page.evaluate(() => getCurrentPages().slice(-1)[0].onPrimary({})); await S(1500); await w.shot('108-lead-start-confirm'); await txt(w,'confirm');
  console.log(JSON.stringify(await w.log()).slice(-500));
}
