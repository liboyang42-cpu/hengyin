import { run, S } from './lib.mjs';
await run({ path: 'pages/team/detail/index?teamId=1', identity: 'j2-m2', agree: true }, async (w) => {
  await S(3000); console.log('TZ', await w.eval('new Date().getTimezoneOffset()')); await w.say('team'); await w.shot('82-member-team-created');
});
