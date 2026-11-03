// Tileable 3D noise volumes for the clouds (Perlin-Worley shape + Worley detail).

function hash3(x, y, z, seed) {
  let h = Math.imul(x, 374761393) ^ Math.imul(y, 668265263) ^ Math.imul(z, 2147483647) ^ Math.imul(seed, 1274126177);
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  h ^= h >>> 16;
  return (h >>> 0) / 4294967296;
}

const mod = (a, n) => ((a % n) + n) % n;
const fade = (t) => t * t * t * (t * (t * 6 - 15) + 10);

const GRADS = [[1,1,0],[-1,1,0],[1,-1,0],[-1,-1,0],[1,0,1],[-1,0,1],[1,0,-1],[-1,0,-1],[0,1,1],[0,-1,1],[0,1,-1],[0,-1,-1]];

function perlin(x, y, z, period, seed) {
  const xi = Math.floor(x), yi = Math.floor(y), zi = Math.floor(z);
  const xf = x - xi, yf = y - yi, zf = z - zi;
  const grad = (ix, iy, iz, dx, dy, dz) => {
    const h = Math.floor(hash3(mod(ix, period), mod(iy, period), mod(iz, period), seed) * 12);
    const g = GRADS[h];
    return g[0] * dx + g[1] * dy + g[2] * dz;
  };
  const u = fade(xf), v = fade(yf), w = fade(zf);
  const l = (a, b, t) => a + (b - a) * t;
  return l(
    l(l(grad(xi, yi, zi, xf, yf, zf), grad(xi + 1, yi, zi, xf - 1, yf, zf), u),
      l(grad(xi, yi + 1, zi, xf, yf - 1, zf), grad(xi + 1, yi + 1, zi, xf - 1, yf - 1, zf), u), v),
    l(l(grad(xi, yi, zi + 1, xf, yf, zf - 1), grad(xi + 1, yi, zi + 1, xf - 1, yf, zf - 1), u),
      l(grad(xi, yi + 1, zi + 1, xf, yf - 1, zf - 1), grad(xi + 1, yi + 1, zi + 1, xf - 1, yf - 1, zf - 1), u), v),
    w);
}

function worley(x, y, z, period, seed) {
  const xi = Math.floor(x), yi = Math.floor(y), zi = Math.floor(z);
  let d = 9;
  for (let k = -1; k <= 1; k++)
    for (let j = -1; j <= 1; j++)
      for (let i = -1; i <= 1; i++) {
        const cx = xi + i, cy = yi + j, cz = zi + k;
        const px = mod(cx, period), py = mod(cy, period), pz = mod(cz, period);
        const fx = cx + hash3(px, py, pz, seed) - x;
        const fy = cy + hash3(px, py, pz, seed + 1) - y;
        const fz = cz + hash3(px, py, pz, seed + 2) - z;
        d = Math.min(d, fx * fx + fy * fy + fz * fz);
      }
  return 1 - Math.min(1, Math.sqrt(d));
}

const remap = (v, a, b, c, d) => c + ((v - a) / (b - a)) * (d - c);
const clamp01 = (v) => Math.min(1, Math.max(0, v));

function worleyFbm(x, y, z, f, seed) {
  return worley(x * f, y * f, z * f, f, seed) * 0.625 + worley(x * f * 2, y * f * 2, z * f * 2, f * 2, seed + 5) * 0.25 + worley(x * f * 4, y * f * 4, z * f * 4, f * 4, seed + 9) * 0.125;
}

// RGBA8: R Perlin-Worley, G/B/A Worley fbm at rising frequency.
export function shapeVolume(n = 96) {
  const out = new Uint8Array(n * n * n * 4);
  let o = 0;
  for (let z = 0; z < n; z++)
    for (let y = 0; y < n; y++)
      for (let x = 0; x < n; x++) {
        const u = x / n, v = y / n, w = z / n;
        let p = 0, amp = 1, tot = 0;
        for (let oct = 0; oct < 4; oct++) {
          const f = 4 * 2 ** oct;
          p += perlin(u * f, v * f, w * f, f, 11 + oct) * amp;
          tot += amp;
          amp *= 0.5;
        }
        p = clamp01(p / tot * 0.9 + 0.5);
        const w1 = worleyFbm(u, v, w, 4, 21);
        const pw = clamp01(remap(p, w1 - 1, 1, 0, 1));
        out[o++] = pw * 255;
        out[o++] = w1 * 255;
        out[o++] = worleyFbm(u, v, w, 8, 41) * 255;
        out[o++] = worleyFbm(u, v, w, 16, 61) * 255;
      }
  return out;
}

export function detailVolume(n = 32) {
  const out = new Uint8Array(n * n * n * 4);
  let o = 0;
  for (let z = 0; z < n; z++)
    for (let y = 0; y < n; y++)
      for (let x = 0; x < n; x++) {
        const u = x / n, v = y / n, w = z / n;
        out[o++] = worleyFbm(u, v, w, 2, 71) * 255;
        out[o++] = worleyFbm(u, v, w, 4, 81) * 255;
        out[o++] = worleyFbm(u, v, w, 8, 91) * 255;
        out[o++] = 255;
      }
  return out;
}
