// Earth textures painted from Natural Earth vectors (public domain) on Canvas 2D.
// Everything is stylised: soft saturated colour, no photo data.

function rng(seed) {
  let s = seed >>> 0;
  return () => {
    s = (s + 0x6d2b79f5) >>> 0;
    let t = s;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function canvas(w, h) {
  const c = document.createElement('canvas');
  c.width = w;
  c.height = h;
  return c;
}

function ringPath(ctx, ring, proj) {
  for (let i = 0; i < ring.length; i++) {
    const [x, y] = proj(ring[i][0], ring[i][1]);
    if (i === 0) ctx.moveTo(x, y);
    else ctx.lineTo(x, y);
  }
  ctx.closePath();
}

function fillGeo(ctx, features, proj, style) {
  ctx.fillStyle = style;
  for (const f of features) {
    const g = f.geometry;
    if (!g) continue;
    const polys = g.type === 'Polygon' ? [g.coordinates] : g.type === 'MultiPolygon' ? g.coordinates : [];
    ctx.beginPath();
    for (const poly of polys) for (const ring of poly) ringPath(ctx, ring, proj);
    ctx.fill('evenodd');
  }
}

function blurred(src, px) {
  const c = canvas(src.width, src.height);
  const ctx = c.getContext('2d');
  ctx.filter = `blur(${px}px)`;
  ctx.drawImage(src, 0, 0);
  return c;
}

// Smooth multi-octave value noise as a grey canvas.
function noiseCanvas(w, h, seed, base, octaves, blurPx) {
  const r = rng(seed);
  const c = canvas(w, h);
  const ctx = c.getContext('2d');
  ctx.fillStyle = '#808080';
  ctx.fillRect(0, 0, w, h);
  let amp = 0.5;
  for (let o = 0; o < octaves; o++) {
    const gw = Math.max(2, Math.round(base * 2 ** o));
    const gh = Math.max(2, Math.round((gw * h) / w));
    const s = canvas(gw, gh);
    const sctx = s.getContext('2d');
    const img = sctx.createImageData(gw, gh);
    for (let i = 0; i < gw * gh; i++) {
      const v = Math.floor(r() * 255);
      img.data[i * 4] = img.data[i * 4 + 1] = img.data[i * 4 + 2] = v;
      img.data[i * 4 + 3] = 255;
    }
    sctx.putImageData(img, 0, 0);
    ctx.globalAlpha = amp;
    ctx.imageSmoothingEnabled = true;
    ctx.imageSmoothingQuality = 'high';
    ctx.globalCompositeOperation = 'overlay';
    ctx.filter = `blur(${Math.max(1, (w / gw) * 0.6)}px)`;
    ctx.drawImage(s, 0, 0, w, h);
    amp *= 0.6;
  }
  ctx.filter = 'none';
  ctx.globalAlpha = 1;
  ctx.globalCompositeOperation = 'source-over';
  return blurPx ? blurred(c, blurPx) : c;
}

function byClass(regions, cls) {
  return regions.features.filter((f) => cls.includes(f.properties.FEATURECLA));
}

function byName(regions, names) {
  return regions.features.filter((f) => names.includes(String(f.properties.NAME).toUpperCase()));
}

// Paint one set of textures for an equirectangular window.
// win: { lon0, lon1, lat0, lat1, w, h }
export function paintSet(data, win, seed) {
  const { w, h } = win;
  const pxPerDeg = w / (win.lon1 - win.lon0);
  const k = pxPerDeg / 11.38; // blur radii are tuned for the 4096 px world map
  const proj = (lon, lat) => [((lon - win.lon0) / (win.lon1 - win.lon0)) * w, ((win.lat1 - lat) / (win.lat1 - win.lat0)) * h];
  const latAtY = (y) => win.lat1 - (y / h) * (win.lat1 - win.lat0);

  // Land mask (lakes cut out).
  const mask = canvas(w, h);
  {
    const ctx = mask.getContext('2d');
    ctx.fillStyle = '#000';
    ctx.fillRect(0, 0, w, h);
    fillGeo(ctx, data.land.features, proj, '#fff');
    fillGeo(ctx, data.lakes.features, proj, '#000');
  }
  const maskSoft = blurred(mask, 0.8 * k);
  const shelf = blurred(mask, 14 * k);
  const inland = blurred(mask, 40 * k);

  // Ocean.
  const ocean = canvas(w, h);
  {
    const ctx = ocean.getContext('2d');
    ctx.drawImage(bandStrip(h, latAtY, [
      [90, [18, 58, 102]], [60, [23, 80, 126]], [35, [27, 106, 146]], [15, [29, 127, 158]], [0, [31, 134, 163]],
      [-15, [29, 127, 158]], [-35, [27, 106, 146]], [-60, [23, 80, 126]], [-90, [18, 58, 102]],
    ]), 0, 0, w, h);
    // Shallow shelf: turquoise halo hugging coasts.
    const halo = canvas(w, h);
    const hctx = halo.getContext('2d');
    const id = hctx.createImageData(w, h);
    const sd = shelf.getContext('2d').getImageData(0, 0, w, h).data;
    for (let i = 0; i < id.data.length; i += 4) {
      id.data[i] = 0x3f; id.data[i + 1] = 0xb7; id.data[i + 2] = 0xb9;
      id.data[i + 3] = Math.min(255, sd[i] * 1.1);
    }
    hctx.putImageData(id, 0, 0);
    ctx.globalAlpha = 0.55;
    ctx.drawImage(halo, 0, 0);
    ctx.globalAlpha = 1;
    const n = noiseCanvas(w, h, seed + 1, 24, 4, 3 * k);
    ctx.globalCompositeOperation = 'soft-light';
    ctx.globalAlpha = 0.35;
    ctx.drawImage(n, 0, 0);
    ctx.globalAlpha = 1;
    ctx.globalCompositeOperation = 'source-over';
  }

  // Land colour: latitude bands, then biomes from Natural Earth regions.
  const land = canvas(w, h);
  {
    const ctx = land.getContext('2d');
    const bands = [
      [90, [232, 236, 240]], [72, [196, 190, 170]], [62, [126, 140, 104]], [52, [92, 130, 88]],
      [40, [124, 152, 84]], [30, [176, 160, 98]], [20, [168, 150, 84]], [10, [88, 140, 70]],
      [0, [70, 132, 64]], [-10, [88, 140, 70]], [-22, [172, 148, 88]], [-35, [138, 150, 88]],
      [-50, [110, 132, 92]], [-62, [200, 200, 196]], [-90, [236, 240, 244]],
    ];
    ctx.drawImage(bandStrip(h, latAtY, bands), 0, 0, w, h);

    const layer = (features, col, alpha, blurPx) => {
      const c = canvas(w, h);
      const cctx = c.getContext('2d');
      cctx.filter = `blur(${blurPx}px)`;
      fillGeo(cctx, features, proj, col);
      ctx.globalAlpha = alpha;
      ctx.drawImage(c, 0, 0);
      ctx.globalAlpha = 1;
    };
    const R = data.regions;
    layer(byClass(R, ['Desert']), '#e0a85e', 0.92, 10 * k);
    layer(byName(R, ['TAKLIMAKAN DESERT', 'GOBI DESERT', 'SAHARA', 'ARABIAN DESERT', 'RUB\' AL KHALI']), '#e8b26a', 0.6, 6 * k);
    layer(byClass(R, ['Tundra']), '#a59a86', 0.55, 12 * k);
    layer(byClass(R, ['Plateau']), '#b58a5c', 0.42, 10 * k);
    layer(byName(R, ['PLATEAU OF TIBET']), '#a77d58', 0.55, 8 * k);
    layer(byClass(R, ['Range/mtn']), '#7f6048', 0.5, 6 * k);
    layer(byClass(R, ['Plain', 'Delta', 'Basin', 'Lowland', 'Valley', 'Wetlands']), '#6c9e54', 0.35, 10 * k);
    layer(byName(R, ['NORTH CHINA PLAIN', 'SICHUAN BASIN']), '#7aa65a', 0.4, 8 * k);
    layer(data.glaciers.features, '#f2f5f8', 0.95, 2 * k);

    // Interior darkening and gentle variation.
    const n = noiseCanvas(w, h, seed + 7, 36, 5, 1.5 * k);
    ctx.globalCompositeOperation = 'soft-light';
    ctx.globalAlpha = 0.7;
    ctx.drawImage(n, 0, 0);
    ctx.globalCompositeOperation = 'multiply';
    ctx.globalAlpha = 0.12;
    ctx.drawImage(inland, 0, 0);
    ctx.globalAlpha = 1;
    ctx.globalCompositeOperation = 'destination-in';
    ctx.drawImage(alphaFromGrey(maskSoft), 0, 0);
    ctx.globalCompositeOperation = 'source-over';
  }

  const day = canvas(w, h);
  {
    const ctx = day.getContext('2d');
    ctx.drawImage(ocean, 0, 0);
    ctx.drawImage(land, 0, 0);
  }

  // Relief: mountains and plateaus raised, land slightly above sea.
  const height = canvas(w, h);
  {
    const ctx = height.getContext('2d');
    ctx.fillStyle = '#000';
    ctx.fillRect(0, 0, w, h);
    ctx.globalAlpha = 0.25;
    ctx.drawImage(blurred(mask, 3 * k), 0, 0);
    ctx.globalAlpha = 1;
    const add = (features, col, blurPx) => {
      const c = canvas(w, h);
      const cctx = c.getContext('2d');
      cctx.filter = `blur(${blurPx}px)`;
      fillGeo(cctx, features, proj, col);
      ctx.globalCompositeOperation = 'lighter';
      ctx.drawImage(c, 0, 0);
      ctx.globalCompositeOperation = 'source-over';
    };
    add(byClass(data.regions, ['Plateau']), '#303030', 14 * k);
    add(byName(data.regions, ['PLATEAU OF TIBET']), '#404040', 10 * k);
    add(byClass(data.regions, ['Range/mtn']), '#606060', 5 * k);
    const n = noiseCanvas(w, h, seed + 13, 64, 5, 0.8 * k);
    ctx.globalCompositeOperation = 'multiply';
    ctx.drawImage(n, 0, 0);
    ctx.globalCompositeOperation = 'source-over';
    const b = blurred(height, 1.2 * k);
    ctx.drawImage(b, 0, 0);
  }

  // City lights from populated places, warm, organic scatter, land only.
  const night = canvas(w, h);
  {
    const ctx = night.getContext('2d');
    ctx.fillStyle = '#000';
    ctx.fillRect(0, 0, w, h);
    ctx.globalCompositeOperation = 'lighter';
    const r = rng(seed + 29);
    const gauss = () => {
      let u = 0, v = 0;
      while (u === 0) u = r();
      while (v === 0) v = r();
      return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
    };
    const dot = (x, y, rad, a) => {
      const g = ctx.createRadialGradient(x, y, 0, x, y, rad);
      g.addColorStop(0, `rgba(255,214,150,${a})`);
      g.addColorStop(0.35, `rgba(255,170,90,${a * 0.45})`);
      g.addColorStop(1, 'rgba(255,140,60,0)');
      ctx.fillStyle = g;
      ctx.fillRect(x - rad, y - rad, rad * 2, rad * 2);
    };
    for (const f of data.places.features) {
      const p = f.properties;
      const pop = Math.max(p.pop_max || 0, 1000);
      const [x, y] = proj(p.longitude, p.latitude);
      if (x < -50 || y < -50 || x > w + 50 || y > h + 50) continue;
      const s = Math.log10(pop) - 3; // 0..~4.5
      const rad = (0.9 + s * 1.3) * k;
      dot(x, y, rad * 2.2, Math.min(0.85, 0.12 + s * 0.16));
      const n = Math.floor(s * s * 3);
      for (let i = 0; i < n; i++) {
        const sx = x + gauss() * rad * 2.4;
        const sy = y + gauss() * rad * 2.4;
        dot(sx, sy, (0.6 + r() * 0.9) * k, 0.25 + r() * 0.35);
      }
    }
    ctx.globalCompositeOperation = 'destination-in';
    ctx.drawImage(alphaFromGrey(blurred(mask, 1.5 * k)), 0, 0);
    ctx.globalCompositeOperation = 'destination-over';
    ctx.fillStyle = '#000';
    ctx.fillRect(0, 0, w, h);
    ctx.globalCompositeOperation = 'source-over';
  }

  return { day, night, height, mask: maskSoft };
}

function alphaFromGrey(src) {
  const c = canvas(src.width, src.height);
  const ctx = c.getContext('2d');
  const id = src.getContext('2d').getImageData(0, 0, src.width, src.height);
  for (let i = 0; i < id.data.length; i += 4) {
    id.data[i + 3] = id.data[i];
    id.data[i] = id.data[i + 1] = id.data[i + 2] = 255;
  }
  ctx.putImageData(id, 0, 0);
  return c;
}

function bandStrip(h, latAtY, bands) {
  const strip = canvas(1, h);
  const sctx = strip.getContext('2d');
  const img = sctx.createImageData(1, h);
  for (let y = 0; y < h; y++) {
    const lat = latAtY(y + 0.5);
    let i = 0;
    while (i < bands.length - 2 && lat < bands[i + 1][0]) i++;
    const [la, ca] = bands[i];
    const [lb, cb] = bands[i + 1];
    let t = (la - lat) / (la - lb);
    t = Math.min(1, Math.max(0, t));
    t = t * t * (3 - 2 * t);
    for (let c = 0; c < 3; c++) img.data[y * 4 + c] = ca[c] + (cb[c] - ca[c]) * t;
    img.data[y * 4 + 3] = 255;
  }
  sctx.putImageData(img, 0, 0);
  return strip;
}
