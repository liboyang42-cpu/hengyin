import { S, txt } from './lib.mjs';
export default async function (w) {
  await S(5000); await w.tap('去选一场'); await S(1500);
  await w.page.evaluate(() => getCurrentPages().slice(-1)[0].onSessionPick({ currentTarget: { dataset: { id: 18 } } }));
  await S(5000);
  console.log('teams', JSON.stringify(await w.data('directorTeamRows')));
  await w.page.evaluate(() => getCurrentPages().slice(-1)[0].openTeamSheet()); await S(2000);
  await w.shot('127-lead-team-progress'); await txt(w,'teams');
}
