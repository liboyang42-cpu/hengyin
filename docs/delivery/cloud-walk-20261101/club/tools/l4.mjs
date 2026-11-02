import { S, txt } from './lib.mjs';
export default async function (w) {
  await S(4000); await txt(w,'manage'); await w.shot('44-lead-manage-invite');
  await w.scroll(1400); await S(800); await w.shot('44b-lead-manage-lower'); await txt(w,'lower'); await w.tap('收到的合作邀约'); await S(2500); await txt(w,'recv'); await w.shot('45-lead-invite-list');
  await w.shot('45-lead-invite-list'); await w.tap('城瘾跑团'); await S(3000); await w.shot('46-lead-invite-open'); await txt(w,'inv'); console.log(JSON.stringify(await w.route()));
}
export async function more(w) {}
