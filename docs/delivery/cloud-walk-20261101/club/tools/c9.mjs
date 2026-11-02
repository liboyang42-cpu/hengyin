import { S, txt } from './lib.mjs';
export default async function (w) {
  await S(5000); await w.tap('去选一场'); await S(1500);
  await w.page.evaluate(() => getCurrentPages().slice(-1)[0].onSessionPick({ currentTarget: { dataset: { id: 18 } } }));
  await S(5000);
  await w.page.evaluate(() => getCurrentPages().slice(-1)[0].onPrimary({})); await S(1500); await w.shot('108-lead-start-confirm'); await txt(w,'confirm');
  await w.tap('开始活动', {exact:true, nth:0}).catch(e => console.log('nth1', e.message)); await S(4000);
  await w.shot('109-lead-running'); await txt(w,'running');
  for (let i=1;i<=4;i++){ await w.scroll(650); await S(500); await w.shot('110-lead-running-'+i); await txt(w,'run'+i); }
}
