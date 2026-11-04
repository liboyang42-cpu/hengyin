import { run, S } from './lib.mjs';
import * as fs from 'node:fs';
// H2: lead opens 团码 on the director page
await run({ path: 'pages/club/topic-detail/index?topicId=990014&clubId=990004&activityId=9', identity: 'j2-lead-a', agree: true }, async (w) => {
  await S(3500); await w.tap('团码', { exact: true }); await S(3000); await w.say('code'); await w.shot('124-lead-group-code');
  const n = (await w.net()).filter(x => /code|qr|checkin/i.test(x.url)).map(x => x.method + ' ' + x.url + ' ' + (x.resBody || '').slice(0, 300));
  console.log('NETS', JSON.stringify(n));
});
