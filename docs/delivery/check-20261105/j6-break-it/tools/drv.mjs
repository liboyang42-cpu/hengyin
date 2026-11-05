// j6 driver: wraps rig launch(), forces 2x screenshots, JPEG under 200KB.
import fs from 'node:fs'; import path from 'node:path';
import { launch } from '/root/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs';
export const SHOTS = process.env.J6_SHOTS || '/home/user/hengyin/docs/delivery/check-20261105/j6-break-it/shots';
export async function open(opts) {
  const w = await launch(opts);
  await w.page.setViewport({ width: 390, height: 844, deviceScaleFactor: 2 });
  await new Promise(r => setTimeout(r, 400));
  w.shot2 = async (name) => {
    fs.mkdirSync(path.dirname(path.join(SHOTS, name)), { recursive: true });
    const f = path.join(SHOTS, name + '.jpg'); let q = 70, buf;
    do { buf = await w.page.screenshot({ type: 'jpeg', quality: q, clip: { x: 0, y: 0, width: 390, height: 844 } }); q -= 10; } while (buf.length > 200000 && q > 20);
    fs.writeFileSync(f, buf); return f;
  };
  return w;
}
export const sleep = (ms) => new Promise(r => setTimeout(r, ms));
