import { S, txt } from './lib.mjs';
export default async function (w) {
  await S(5000); await w.tap('去选一场'); await S(1500);
  await w.page.evaluate(() => getCurrentPages().slice(-1)[0].onSessionPick({ currentTarget: { dataset: { id: 18 } } }));
  await S(5000);
  console.log('recapMetrics', JSON.stringify(await w.data('recapMetrics')));
  console.log('stations', JSON.stringify((await w.data('recap')||{}).stations).slice(0,300));
}
