import { S, txt } from './lib.mjs';
export default async function (w) {
  await S(4500);
  await w.tap('去选一场'); await S(1500); await w.shot('68-lead-pick-session');
  await w.page.evaluate(() => getCurrentPages().slice(-1)[0].onSessionPick({ currentTarget: { dataset: { id: 16 } } }));
  await S(4500); console.log(JSON.stringify(await w.route())); await w.shot('69-lead-session-pre'); await txt(w,'dir');
  console.log('canDirect', await w.page.evaluate(() => { const p = getCurrentPages()[0]; return JSON.stringify({c: p._canDirect, d: p.data.directorActive}); }));
}
