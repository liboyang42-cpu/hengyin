import { S, txt } from './lib.mjs';
export default async function (w) {
  await S(5000); await w.tap('去选一场'); await S(1500);
  await w.page.evaluate(() => getCurrentPages().slice(-1)[0].onSessionPick({ currentTarget: { dataset: { id: 18 } } }));
  await S(5000);
  for (const k of ['canUnlockChapter','directorTeamRows','incidentRows','statusKey','availableActions']) { try { console.log(k, JSON.stringify(await w.data(k)).slice(0,500)); } catch (e) {} }
  await w.scroll(3000); await S(800); await w.shot('124-lead-director-bottom'); await txt(w,'bottom');
}
