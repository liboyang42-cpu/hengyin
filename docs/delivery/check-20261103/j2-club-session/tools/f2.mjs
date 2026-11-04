import { run, S } from './lib.mjs';
// F2: member: theme page → 查看场次 (bmClick invoked directly: taps on this page do not reach handlers in the rig) → session sheet → checkout
export const call = (w, m, arg) => w.eval(`(()=>{const p=getCurrentPages().slice(-1)[0]; return p['${m}'](${arg || "{ currentTarget: { dataset: {} } }"});})()`);
if (process.argv[1].endsWith('f2.mjs')) await run({ path: 'pages/topic/index/index?id=990014', identity: 'j2-m2', agree: true }, async (w) => {
  await S(3000); for (let i = 0; i < 3; i++) { await w.scroll(800); await S(400); }
  await call(w, 'bmClick'); await S(2000); console.log('ROUTE2', JSON.stringify(await w.route())); await w.say('sessions'); await w.shot('74-member-session-sheet');
});
