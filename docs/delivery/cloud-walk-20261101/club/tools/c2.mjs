import { S, txt } from './lib.mjs';
export default async function (w) {
  await S(5000); await w.shot('73-lead-co-td'); await txt(w,'td');
  await w.tap('去选一场'); await S(1500);
  await w.page.evaluate(() => getCurrentPages().slice(-1)[0].onSessionPick({ currentTarget: { dataset: { id: 18 } } }));
  await S(5000); console.log(JSON.stringify(await w.route())); await w.shot('74-lead-director-pre'); await txt(w,'dir');
  for (let i=1;i<=3;i++){ await w.scroll(700); await S(600); await w.shot('75-lead-director-pre-'+i); await txt(w,'dir'+i); }
}
