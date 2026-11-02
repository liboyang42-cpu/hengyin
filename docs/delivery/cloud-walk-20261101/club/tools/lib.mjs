export const S = (ms) => new Promise(r => setTimeout(r, ms));
export async function ty(w, ph, text) {
  const h = await w.page.$(`pierce/input[placeholder="${ph}"]`) || await w.page.$(`pierce/textarea[placeholder="${ph}"]`);
  if (!h) throw new Error('no input ' + ph);
  await h.click({ clickCount: 3 }); await h.type(String(text), { delay: 10 }); await S(300);
}
export async function txt(w, label) { const t = await w.text(); console.log('TEXT[' + label + ']', t); return t; }
