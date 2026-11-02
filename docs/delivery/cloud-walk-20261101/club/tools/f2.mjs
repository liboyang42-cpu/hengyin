import { S, txt } from './lib.mjs';
export default async function (w) {
  await S(4500); await w.tap('帖文', {exact:true}); await S(3000); await w.shot('94-member-discover-posts'); await txt(w,'posts');
}
