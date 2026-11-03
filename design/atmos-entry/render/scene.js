import * as THREE from 'three';
import { shapeVolume, detailVolume } from './noise3d.js';

// ---------- timeline ----------
export const FPS = 30;
export const DURATION = 3.8;
export const FRAMES = Math.round(FPS * DURATION);

const clamp01 = (v) => Math.min(1, Math.max(0, v));
const smooth = (a, b, t) => { const x = clamp01((t - a) / (b - a)); return x * x * (3 - 2 * x); };
const smoother = (a, b, t) => { const x = clamp01((t - a) / (b - a)); return x * x * x * (x * (x * 6 - 15) + 10); };
const lerp = (a, b, t) => a + (b - a) * t;
const rad = (d) => (d * Math.PI) / 180;

// Dive progress: an almost still hold, then an accelerating fall whose speed peaks inside the clouds.
function diveEase(t) {
  const x = clamp01(t / 3.05);
  return x < 1 ? Math.pow(x, 2.9) * (1.0 - 0.15 * Math.sin(Math.PI * x)) : 1;
}
// Heavy rotation: already moving at a slow constant rate on frame 0 (no ease-in from rest),
// then settling onto China as the dive takes over.
function turnEase(t) {
  const s = (u) => smoother(-1.6, 2.55, u);
  return (s(t) - s(0)) / (1 - s(0));
}

export const CHINA = { lon: 118.0, lat: 31.0 };
export const CUT = 2.7;

export function params(t) {
  const alt = Math.exp(lerp(Math.log(4.5), Math.log(0.06), diveEase(t)));
  const turn = turnEase(t);
  return {
    t,
    alt,
    lon: lerp(150, CHINA.lon, turn),
    lat: lerp(14, CHINA.lat, turn),
    lookY: lerp(0.95, 0.0, smoother(0.2, 2.0, t)),
    roll: 0,
    // extra cloud over the dive target so the coastline never reads as a map
    cloudBoost: smoother(1.3, 2.2, t),
    coverage: lerp(0.0, 1.0, smoother(2.2, 2.62, t)) - 0.22 * smoother(2.85, 3.5, t),
    densityBoost: 1 + 3.5 * Math.exp(-(((t - CUT) / 0.09) ** 2)),
    bgMix: t >= CUT ? 1 : 0,
    part: smoother(2.85, 3.8, t),
    white: smooth(3.5, 3.8, t),
  };
}

// ---------- shaders ----------
const COMMON = /* glsl */ `
  #define PI 3.14159265359
  float hash12(vec2 p){ vec3 p3 = fract(vec3(p.xyx) * .1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
  float hash13(vec3 p3){ p3 = fract(p3 * .1031); p3 += dot(p3, p3.zyx + 31.32); return fract((p3.x + p3.y) * p3.z); }
  vec3 hash33(vec3 p3){ p3 = fract(p3 * vec3(.1031, .1030, .0973)); p3 += dot(p3, p3.yxz + 33.33); return fract((p3.xxy + p3.yxx) * p3.zyx); }
`;

const fsQuadVert = /* glsl */ `
  out vec2 vUv;
  void main(){ vUv = uv; gl_Position = vec4(position.xy, 0.0, 1.0); }
`;

// Catalogue stars (Yale BSC5) as sub-pixel point sprites. Direction only, so they move with the camera's rotation and never with its travel.
const starVert = /* glsl */ `
  in float aFlux; in vec3 aCol;
  out float vFlux; out vec3 vCol;
  uniform float uSize;
  void main(){
    vFlux = aFlux; vCol = aCol;
    vec3 d = mat3(viewMatrix) * normalize(position);
    gl_Position = projectionMatrix * vec4(d, 1.0);
    gl_PointSize = uSize;
  }
`;
const starFrag = /* glsl */ `
  in float vFlux; in vec3 vCol;
  uniform float uSize;
  out vec4 outColor;
  void main(){
    vec2 d = (gl_PointCoord - 0.5) * uSize;
    float sigma = 0.5 + 0.18 * clamp(log(vFlux + 1.0), 0.0, 1.5);
    float psf = exp(-dot(d, d) / (2.0 * sigma * sigma)) / (2.0 * PI * sigma * sigma);
    outColor = vec4(vCol * vFlux * psf, 0.0);
  }
`;

// The planet is ray-traced per pixel: NASA surface, NASA cloud shell with its shadow, Black Marble lights,
// sun glint, and single-scattering Rayleigh + Mie atmosphere at real scale heights.
const globeFrag = /* glsl */ `
  ${COMMON}
  in vec2 vUv;
  uniform sampler2D tDay, tAux, tDayC, tAuxC;
  uniform vec4 uWin;
  uniform mat4 uInvProj, uCamWorld; uniform vec3 uCam, uSun; uniform mat3 uW2O; uniform vec2 uRes;
  uniform float uTime, uSunE, uCloudBoost, uFrame; uniform vec3 uTarget;
  out vec4 outColor;

  const float KM = 1.0 / 6371.0;
  const float RC = 1.0 + 7.0 * KM;          // cloud shell
  const float RA = 1.0 + 90.0 * KM;         // top of atmosphere
  const float HR = 8.0 * KM, HM = 1.2 * KM; // Rayleigh / Mie scale heights
  const vec3 BR = vec3(5.802e-3, 13.558e-3, 33.1e-3) / KM; // Rayleigh scattering per unit radius
  const float BMS = 1.6e-3 / KM, BME = 1.8e-3 / KM;        // Mie scattering / extinction (clear maritime air)
  const float GM = 0.8;

  vec2 sph(vec3 ro, vec3 rd, float r){ float b = dot(ro, rd); float c = dot(ro, ro) - r * r; float h = b * b - c; if (h < 0.0) return vec2(1e9, -1e9); h = sqrt(h); return vec2(-b - h, -b + h); }

  // Schueler's Chapman-function approximation: optical depth toward the sun without a light march.
  float chapman(float X, float h, float c){
    float s = sqrt(X + h);
    if (c >= 0.0) return s / (s * c + 1.0) * exp(-h);
    float x0 = sqrt(1.0 - c * c) * (X + h);
    return 2.0 * sqrt(x0) * exp(X - x0) - s / (1.0 - s * c) * exp(-h);
  }
  vec3 sunT(vec3 p){
    float r = length(p); float h = max(r - 1.0, 0.0); float c = dot(p / r, uSun);
    float odR = HR * chapman(1.0 / HR, h / HR, c);
    float odM = HM * chapman(1.0 / HM, h / HM, c);
    return exp(-(BR * odR + BME * odM));
  }

  vec2 lonlat(vec3 n){ return vec2(atan(n.x, n.z), asin(clamp(n.y, -1.0, 1.0))); }
  vec2 toUV(vec2 ll){ return vec2((ll.x + PI) / (2.0 * PI), (ll.y + 0.5 * PI) / PI); }
  // seam-safe derivatives (wrap at lon = 180)
  void grads(vec2 uv, out vec2 dx, out vec2 dy){
    vec2 uvB = vec2(fract(uv.x + 0.5) - 0.5, uv.y);
    vec2 dxA = dFdx(uv), dyA = dFdy(uv), dxB = dFdx(uvB), dyB = dFdy(uvB);
    dx = abs(dxA.x) < abs(dxB.x) ? dxA : dxB;
    dy = abs(dyA.x) < abs(dyB.x) ? dyA : dyB;
  }
  float cloudAt(vec3 n, float lodBias){
    vec2 ll = lonlat(n); ll.x += uTime * 0.0025; // slow weather drift
    vec2 uv = toUV(ll); vec2 dx, dy; grads(uv, dx, dy);
    float s = exp2(lodBias);
    float c = textureGrad(tAux, uv, dx * s, dy * s).r;
    // over the dive target, lay a real overcast field from the NASA map (a North Pacific storm field near 150 W, chosen free of swath seams),
    // so the coastline disappears under genuine weather rather than a painted blob
    float near = smoothstep(0.32, 0.10, length(n - uTarget));
    if (near > 0.0 && uCloudBoost > 0.0) {
      vec2 src = toUV(vec2(radians(-150.0), radians(42.0))), tgt = toUV(vec2(radians(118.0), radians(31.0)));
      vec2 k = vec2(cos(radians(31.0)) / cos(radians(42.0)), 1.0);
      vec2 suv = src + (uv - tgt) * k;
      float cs = textureGrad(tAux, suv, dx * s * k.x, dy * s).r;
      c = mix(c, max(c, min(1.0, cs * 1.8)), uCloudBoost * near);
    }
    return c;
  }
  float cloudAlpha(float c){ float a = smoothstep(0.10, 0.92, c); return a * a * (1.6 - 0.6 * a); }

  float ggx(float nh, float a){ float a2 = a * a; float d = nh * nh * (a2 - 1.0) + 1.0; return a2 / (PI * d * d); }

  void main(){
    vec4 ndc = vec4(vUv * 2.0 - 1.0, 1.0, 1.0);
    vec4 v = uInvProj * ndc; v /= v.w;
    vec3 rdW = normalize((uCamWorld * vec4(normalize(v.xyz), 0.0)).xyz);
    vec3 ro = uW2O * uCam; vec3 rd = normalize(uW2O * rdW); vec3 sun = uSun;
    float pixAng = 2.0 * tan(radians(25.0)) / uRes.y;

    vec2 ta = sph(ro, rd, RA);
    if (ta.x > 1e8 || ta.y <= 0.0) { outColor = vec4(0.0); return; }
    float tca = -dot(ro, rd); float b = length(ro + rd * tca);
    vec2 tg = sph(ro, rd, 1.0);
    bool hit = tg.x < 1e8 && tg.x > 0.0;
    float cov = clamp((1.0 - b) / (pixAng * max(tca, 1e-4)) + 0.5, 0.0, 1.0); // analytic limb anti-aliasing

    // ---- atmosphere: single scattering along the view ray ----
    float t0 = max(ta.x, 0.0);
    float t1 = hit ? tg.x : ta.y;
    const int N = 32;
    float ds = (t1 - t0) / float(N);
    const float jit = 0.5; // fixed midpoint: per-pixel jitter showed as grain on the dark ocean
    float odR = 0.0, odM = 0.0;
    vec3 sR = vec3(0.0), sM = vec3(0.0);
    for (int i = 0; i < N; i++) {
      vec3 p = ro + rd * (t0 + ds * (float(i) + jit));
      float h = max(length(p) - 1.0, 0.0);
      float dR = exp(-h / HR) * ds, dM = exp(-h / HM) * ds;
      odR += dR; odM += dM;
      vec3 Tv = exp(-(BR * (odR - 0.5 * dR) + BME * (odM - 0.5 * dM)));
      vec3 Ts = sunT(p);
      sR += Tv * Ts * dR; sM += Tv * Ts * dM;
    }
    vec3 Tview = exp(-(BR * odR + BME * odM));
    float mu = dot(rd, sun);
    float phR = 3.0 / (16.0 * PI) * (1.0 + mu * mu);
    float g2 = GM * GM;
    float phM = 3.0 / (8.0 * PI) * ((1.0 - g2) * (1.0 + mu * mu)) / ((2.0 + g2) * pow(1.0 + g2 - 2.0 * GM * mu, 1.5));
    vec3 inscatter = uSunE * (sR * BR * phR + sM * BMS * phM);

    vec3 L = inscatter;
    if (cov > 0.0) {
      vec3 n = hit ? normalize(ro + rd * tg.x) : normalize(ro + rd * tca);
      vec2 ll = lonlat(n); vec2 uv = toUV(ll); vec2 dx, dy; grads(uv, dx, dy);
      vec3 day = textureGrad(tDay, uv, dx, dy).rgb;
      vec3 aux = textureGrad(tAux, uv, dx, dy).rgb;
      float water = aux.b;
      vec2 cuv = vec2((ll.x - uWin.x) / (uWin.y - uWin.x), (ll.y - uWin.z) / (uWin.w - uWin.z));
      float cw = smoothstep(0.0, 0.08, cuv.x) * smoothstep(1.0, 0.92, cuv.x) * smoothstep(0.0, 0.1, cuv.y) * smoothstep(1.0, 0.9, cuv.y);
      if (cw > 0.0) {
        vec2 cdx = dFdx(cuv), cdy = dFdy(cuv);
        day = mix(day, textureGrad(tDayC, cuv, cdx, cdy).rgb, cw);
        water = mix(water, textureGrad(tAuxC, cuv, cdx, cdy).b, cw);
      }
      float lights = aux.g;

      // ocean: deep navy body (BMNG paints sea-floor relief, which is not visible from orbit); only shelves keep their tint
      float lum = dot(day, vec3(0.2126, 0.7152, 0.0722));
      vec3 navy = vec3(0.0028, 0.0068, 0.0185);
      vec3 sea = mix(navy, day * 0.55, smoothstep(0.035, 0.16, lum));
      vec3 albedo = mix(day, sea, water);

      vec3 V = -rd;
      float nl = dot(n, sun);
      vec3 Tg = sunT(n * 1.00002);

      // clouds: separate shell above the ground (parallax) and its shadow cast along the sun direction
      vec3 nc = n;
      if (hit) { vec2 tc = sph(ro, rd, RC); if (tc.x > 0.0 && tc.x < 1e8) nc = normalize(ro + rd * tc.x); }
      float cA = cloudAlpha(cloudAt(nc, 0.0));
      float shadow = 0.0;
      if (nl > 0.0) {
        vec2 tsh = sph(n, sun, RC);
        vec3 ns = normalize(n + sun * tsh.y);
        shadow = cloudAlpha(cloudAt(ns, 2.5));
      }

      vec3 Lg = albedo / PI * uSunE * max(nl, 0.0) * Tg * (1.0 - 0.8 * shadow);
      // skylight, faint
      Lg += albedo * uSunE * 0.010 * smoothstep(0.0, 0.5, nl) * vec3(0.55, 0.75, 1.0);
      // sun glint on open water
      vec3 Hh = normalize(sun + V);
      float nv = max(dot(n, V), 1e-3);
      float F = 0.02 + 0.98 * pow(1.0 - max(dot(V, Hh), 0.0), 5.0);
      float spec = ggx(max(dot(n, Hh), 0.0), 0.16) * F / (4.0 * nv) * 0.3;
      Lg += water * (1.0 - 0.8 * shadow) * uSunE * spec * Tg * step(0.0, nl);
      // Black Marble city lights, only past the terminator
      float night = 1.0 - smoothstep(-0.12, 0.03, nl);
      Lg += vec3(1.0, 0.66, 0.36) * pow(lights, 2.2) * 0.22 * night;

      float ncl = dot(nc, sun);
      vec3 Tc = sunT(nc * RC);
      vec3 Lc = vec3(0.92) / PI * uSunE * max(ncl, 0.0) * Tc + vec3(0.92) * uSunE * 0.012 * smoothstep(0.0, 0.5, ncl) * vec3(0.6, 0.75, 1.0);
      vec3 Ls = mix(Lg, Lc, cA * (1.0 - 0.0));
      L += Ls * Tview * cov;
    }
    outColor = vec4(L, cov);
  }
`;

const cloudFrag = /* glsl */ `
  ${COMMON}
  precision highp sampler3D;
  in vec2 vUv;
  uniform sampler3D tShape, tDetail;
  uniform vec3 uCamPos, uFwd, uRight, uUp; uniform float uTanY, uAspect;
  uniform float uCoverage, uDensity, uPart, uTime; uniform vec2 uPartC; uniform vec2 uRes; uniform float uFrame;
  uniform vec3 uSun;
  out vec4 outColor;
  const float TOP = 1.3;
  const float SCALE = 0.8;

  float remap(float v, float a, float b, float c, float d){ return c + (v - a) / (b - a) * (d - c); }

  float density(vec3 p, bool detail){
    float h = clamp(p.z / TOP, 0.0, 1.0);
    float prof = smoothstep(0.0, 0.22, h) * smoothstep(1.0, 0.55, h);
    vec3 q = p * SCALE + vec3(uTime * 0.018, uTime * 0.006, 0.0);
    vec4 s = texture(tShape, q);
    float wf = s.g * 0.625 + s.b * 0.25 + s.a * 0.125;
    float base = clamp(s.r - (1.0 - wf) * 0.3, 0.0, 1.0) * prof;
    float cov = uCoverage;
    float d = clamp(remap(base, 1.0 - cov, 1.0, 0.0, 1.0), 0.0, 1.0);
    // parting: an opening that grows outward around the descent axis, ragged by noise
    if (uPart > 0.0) {
      float r = length((p.xy - uPartC) * vec2(1.0, 0.62)) + (s.g - 0.5) * 0.30 + (s.b - 0.5) * 0.14;
      float R = uPart * 0.52 - 0.08;
      d *= smoothstep(R - 0.02, R + 0.16, r);
    }
    if (d <= 0.0 || !detail) return d * uDensity;
    vec4 dt = texture(tDetail, q * 3.1 + vec3(0.0, 0.0, uTime * 0.02));
    float df = dt.r * 0.625 + dt.g * 0.25 + dt.b * 0.125;
    df = mix(df, 1.0 - df, smoothstep(0.1, 0.6, h));
    d = clamp(remap(d, df * 0.45, 1.0, 0.0, 1.0), 0.0, 1.0);
    return d * uDensity;
  }

  float hg(float c, float g){ float g2 = g * g; return (1.0 - g2) / (4.0 * PI * pow(1.0 + g2 - 2.0 * g * c, 1.5)); }

  void main(){
    vec2 ndc = vUv * 2.0 - 1.0;
    vec3 rd = normalize(uFwd + uRight * ndc.x * uTanY * uAspect + uUp * ndc.y * uTanY);
    vec3 ro = uCamPos;
    if (uCoverage <= 0.001) { outColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
    float tTop = (TOP - ro.z) / rd.z;
    float tBot = (0.0 - ro.z) / rd.z;
    float tA = max(0.0, min(tTop, tBot));
    float tB = max(tTop, tBot);
    if (tB <= 0.0) { outColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
    tB = min(tB, tA + 7.0);
    const int N = 72;
    float ds = (tB - tA) / float(N);
    float jitter = hash13(vec3(gl_FragCoord.xy, uFrame));
    float T = 1.0;
    vec3 L = vec3(0.0);
    float mu = dot(rd, uSun);
    float phase = mix(hg(mu, 0.55), hg(mu, -0.25), 0.45) * 4.0 * PI * 0.6 + 0.4;
    vec3 sunCol = vec3(1.05, 1.0, 0.96) * 1.7;
    vec3 shadowCol = vec3(0.40, 0.36, 0.60);
    vec3 skyCol = vec3(0.86, 0.84, 1.0);
    const float SIGMA = 14.0;
    for (int i = 0; i < N; i++) {
      float t = tA + ds * (float(i) + jitter);
      vec3 p = ro + rd * t;
      float d = density(p, true);
      if (d > 0.002) {
        // self-shadow toward the sun
        float od = 0.0;
        float sl = 0.05;
        vec3 lp = p;
        for (int j = 0; j < 5; j++) { lp += uSun * sl; od += density(lp, false) * sl; sl *= 1.7; }
        float lightT = exp(-od * SIGMA * 0.55) * 0.75 + exp(-od * SIGMA * 0.14) * 0.25;
        float powder = 1.0 - exp(-d * SIGMA * 0.35);
        float h = clamp(p.z / TOP, 0.0, 1.0);
        vec3 amb = mix(shadowCol, skyCol, smoothstep(0.0, 0.9, h)) * 0.36;
        vec3 Sc = sunCol * lightT * phase * mix(0.55, 1.0, powder) + amb;
        float sig = d * SIGMA;
        float Ts = exp(-sig * ds);
        L += T * Sc * (1.0 - Ts);
        T *= Ts;
        if (T < 0.004) { T = 0.0; break; }
      }
    }
    outColor = vec4(L, T);
  }
`;

const compFrag = /* glsl */ `
  ${COMMON}
  in vec2 vUv;
  uniform sampler2D tGlobe, tCloud; uniform float uBgMix, uWhite, uFrame, uExposure, uDeckAll; uniform vec2 uRes; uniform float uUseCloud;
  out vec4 outColor;
  // ACES filmic fit (Narkowicz)
  vec3 aces(vec3 x){ return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0); }
  vec3 toSRGB(vec3 c){ return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c)); }
  void main(){
    vec4 gl = texture(tGlobe, vUv);
    vec3 bg = gl.rgb * uExposure;
    // after the cut the camera is inside the weather: soft sky light behind the deck
    vec3 haze = mix(vec3(0.36, 0.38, 0.64), vec3(0.62, 0.62, 0.86), smoothstep(0.0, 1.0, vUv.y));
    haze += vec3(0.25, 0.22, 0.18) * exp(-pow(length((vUv - vec2(0.75, 0.85)) * vec2(1.0, 0.6)) * 2.2, 2.0));
    bg = mix(bg, haze, uBgMix);
    vec3 col = bg;
    // before the deck closes, weather only forms over the planet, never in empty space
    if (uUseCloud > 0.5) { vec4 c = texture(tCloud, vUv); float m = max(gl.a, uDeckAll); col = mix(bg, bg * c.a + c.rgb, m); }
    col = aces(col);
    col = toSRGB(col);
    col = mix(col, vec3(0.955, 0.945, 0.975), uWhite);
    // triangular dither against banding (applied to every pixel, including black space)
    float n = hash12(gl_FragCoord.xy + uFrame * 37.0) + hash12(gl_FragCoord.xy * 1.37 + uFrame * 11.0) - 1.0;
    col = max(col + n / 255.0, 0.0);
    outColor = vec4(col, 1.0);
  }
`;

// ---------- setup ----------
function imgTex(bitmap, srgb, wrapS = THREE.RepeatWrapping) {
  const t = new THREE.Texture(bitmap);
  t.flipY = false; // bitmaps are created already flipped
  t.colorSpace = srgb ? THREE.SRGBColorSpace : THREE.NoColorSpace;
  t.wrapS = wrapS;
  t.wrapT = THREE.ClampToEdgeWrapping;
  t.anisotropy = 16;
  t.generateMipmaps = true;
  t.minFilter = THREE.LinearMipmapLinearFilter;
  t.magFilter = THREE.LinearFilter;
  t.needsUpdate = true;
  return t;
}

function volTex(data, n) {
  const t = new THREE.Data3DTexture(data, n, n, n);
  t.format = THREE.RGBAFormat;
  t.type = THREE.UnsignedByteType;
  t.minFilter = THREE.LinearFilter;
  t.magFilter = THREE.LinearFilter;
  t.wrapS = t.wrapT = t.wrapR = THREE.RepeatWrapping;
  t.unpackAlignment = 1;
  t.needsUpdate = true;
  return t;
}

// colour of a star from its catalogue temperature, softened toward white as a camera sees it
function starRGB(K) {
  const T = Math.min(40000, Math.max(1000, K)) / 100;
  let r = T <= 66 ? 255 : 329.7 * Math.pow(T - 60, -0.1332);
  let g = T <= 66 ? 99.47 * Math.log(T) - 161.1 : 288.1 * Math.pow(T - 60, -0.0755);
  let b = T >= 66 ? 255 : T <= 19 ? 0 : 138.5 * Math.log(T - 10) - 305.0;
  const c = [r, g, b].map((x) => Math.min(255, Math.max(0, x)) / 255);
  const m = (c[0] + c[1] + c[2]) / 3;
  return c.map((x) => (m + (x - m) * 0.55) / m);
}

export async function setup(data, opts) {
  const W = opts.width, H = opts.height;
  const cloudScale = opts.cloudScale || 0.5;
  const log = opts.log || (() => {});
  const canvas = document.createElement('canvas');
  canvas.width = W;
  canvas.height = H;
  document.body.appendChild(canvas);
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: false, preserveDrawingBuffer: true, alpha: false });
  renderer.setPixelRatio(1);
  renderer.setSize(W, H, false);
  renderer.outputColorSpace = THREE.LinearSRGBColorSpace;
  renderer.autoClear = true;
  log('max texture size ' + renderer.capabilities.maxTextureSize);

  const win = { lon0: 72, lon1: 138, lat0: 14, lat1: 54 };
  log('noise volumes');
  const shape = volTex(shapeVolume(opts.noiseN || 96), opts.noiseN || 96);
  const detail = volTex(detailVolume(32), 32);

  const rtOpts = { type: THREE.HalfFloatType, format: THREE.RGBAFormat, depthBuffer: false, minFilter: THREE.LinearFilter, magFilter: THREE.LinearFilter };
  const globeRT = new THREE.WebGLRenderTarget(W, H, rtOpts);
  const cloudRT = new THREE.WebGLRenderTarget(Math.round(W * cloudScale), Math.round(H * cloudScale), rtOpts);

  const camera = new THREE.PerspectiveCamera(50, W / H, 0.005, 100);
  const quad = new THREE.PlaneGeometry(2, 2);

  // ---- stars ----
  // Catalogue frame rotated so a quiet, sparse part of the sky sits behind the planet.
  const starRot = new THREE.Matrix4().makeRotationFromEuler(new THREE.Euler(rad(opts.starTilt ?? 38), rad(opts.starYaw ?? 205), 0, 'YXZ'));
  const sPos = [], sFlux = [], sCol = [];
  for (const [x, y, z, V, K] of data.stars) {
    if (V > 5.6) continue;
    const p = new THREE.Vector3(x, y, z).applyMatrix4(starRot);
    sPos.push(p.x, p.y, p.z);
    sFlux.push(Math.min(3.0, 2.2 * Math.pow(10, -0.4 * (V - 1.0))));
    sCol.push(...starRGB(K));
  }
  const sGeo = new THREE.BufferGeometry();
  sGeo.setAttribute('position', new THREE.Float32BufferAttribute(sPos, 3));
  sGeo.setAttribute('aFlux', new THREE.Float32BufferAttribute(sFlux, 1));
  sGeo.setAttribute('aCol', new THREE.Float32BufferAttribute(sCol, 3));
  const starMat = new THREE.ShaderMaterial({
    glslVersion: THREE.GLSL3, vertexShader: starVert, fragmentShader: '#define PI 3.14159265359\n' + starFrag,
    depthWrite: false, depthTest: false, transparent: true, uniforms: { uSize: { value: 7 } },
    // pure additive light; alpha untouched so the star layer never counts as planet coverage
    blending: THREE.CustomBlending, blendSrc: THREE.OneFactor, blendDst: THREE.OneFactor, blendSrcAlpha: THREE.ZeroFactor, blendDstAlpha: THREE.OneFactor,
  });
  const starPts = new THREE.Points(sGeo, starMat);
  starPts.frustumCulled = false;
  const starScene = new THREE.Scene();
  starScene.add(starPts);

  // ---- globe ----
  const tx = data.tex;
  const globeMat = new THREE.ShaderMaterial({
    glslVersion: THREE.GLSL3, vertexShader: fsQuadVert, fragmentShader: globeFrag, depthWrite: false, depthTest: false,
    transparent: true, blending: THREE.CustomBlending, blendSrc: THREE.OneFactor, blendDst: THREE.OneMinusSrcAlphaFactor,
    blendSrcAlpha: THREE.OneFactor, blendDstAlpha: THREE.OneMinusSrcAlphaFactor,
    uniforms: {
      tDay: { value: imgTex(tx.world_day, true) }, tAux: { value: imgTex(tx.world_aux, false) },
      tDayC: { value: imgTex(tx.china_day, true, THREE.ClampToEdgeWrapping) }, tAuxC: { value: imgTex(tx.china_aux, false, THREE.ClampToEdgeWrapping) },
      uWin: { value: new THREE.Vector4(rad(win.lon0), rad(win.lon1), rad(win.lat0), rad(win.lat1)) },
      uInvProj: { value: new THREE.Matrix4() }, uCamWorld: { value: new THREE.Matrix4() }, uCam: { value: new THREE.Vector3() },
      uSun: { value: new THREE.Vector3() }, uW2O: { value: new THREE.Matrix3() }, uRes: { value: new THREE.Vector2(W, H) },
      uTime: { value: 0 }, uSunE: { value: opts.sunE ?? 5.0 }, uCloudBoost: { value: 0 }, uFrame: { value: 0 }, uTarget: { value: new THREE.Vector3() },
    },
  });
  const globeScene = new THREE.Scene();
  const gm = new THREE.Mesh(quad, globeMat);
  gm.frustumCulled = false;
  globeScene.add(gm);
  // Sun in the camera frame: up and to the right, ~59 deg off the view axis, so most of the disc is lit and a real terminator crosses the left side.
  const sunCam = new THREE.Vector3(...(opts.sun || [0.82, 0.25, 0.52])).normalize();
  const target = (() => { const lo = rad(CHINA.lon), la = rad(CHINA.lat); return new THREE.Vector3(Math.cos(la) * Math.sin(lo), Math.sin(la), Math.cos(la) * Math.cos(lo)); })();
  globeMat.uniforms.uTarget.value.copy(target);

  const cloudMat = new THREE.ShaderMaterial({
    glslVersion: THREE.GLSL3, vertexShader: fsQuadVert, fragmentShader: cloudFrag, depthWrite: false, depthTest: false,
    uniforms: {
      tShape: { value: shape }, tDetail: { value: detail },
      uCamPos: { value: new THREE.Vector3() }, uFwd: { value: new THREE.Vector3() }, uRight: { value: new THREE.Vector3() }, uUp: { value: new THREE.Vector3() },
      uTanY: { value: Math.tan(rad(25)) }, uAspect: { value: W / H },
      uCoverage: { value: 0 }, uDensity: { value: 1 }, uPart: { value: 0 }, uPartC: { value: new THREE.Vector2() }, uTime: { value: 0 }, uRes: { value: new THREE.Vector2(cloudRT.width, cloudRT.height) }, uFrame: { value: 0 },
      uSun: { value: new THREE.Vector3(0.45, 0.32, 0.83).normalize() },
    },
  });
  const cloudScene = new THREE.Scene();
  const cm = new THREE.Mesh(quad, cloudMat);
  cm.frustumCulled = false;
  cloudScene.add(cm);

  const compMat = new THREE.ShaderMaterial({
    glslVersion: THREE.GLSL3, vertexShader: fsQuadVert, fragmentShader: compFrag, depthWrite: false, depthTest: false,
    uniforms: {
      tGlobe: { value: globeRT.texture }, tCloud: { value: cloudRT.texture }, uBgMix: { value: 0 }, uWhite: { value: 0 },
      uFrame: { value: 0 }, uRes: { value: new THREE.Vector2(W, H) }, uUseCloud: { value: 0 }, uDeckAll: { value: 0 }, uExposure: { value: opts.exposure ?? 1.0 },
    },
  });
  const compScene = new THREE.Scene();
  const qm = new THREE.Mesh(quad, compMat);
  qm.frustumCulled = false;
  compScene.add(qm);

  const earthQ = new THREE.Quaternion();
  function render(frame) {
    const t = frame / FPS;
    const P = params(t);

    camera.position.set(0, 0, 1 + P.alt);
    camera.up.set(Math.sin(P.roll), Math.cos(P.roll), 0);
    camera.lookAt(0, P.lookY * (P.alt / 4.5), 0);
    camera.updateMatrixWorld();
    // Earth orientation: same convention as before (rotateX(lat) then rotateY(-lon)) so the sub-camera point is (lon, lat)
    const eg = new THREE.Object3D();
    eg.rotateX(rad(P.lat));
    eg.rotateY(-rad(P.lon));
    eg.updateMatrixWorld(true);
    earthQ.copy(eg.quaternion);
    const w2o = new THREE.Matrix3().setFromMatrix4(new THREE.Matrix4().makeRotationFromQuaternion(earthQ.clone().invert()));
    const g = globeMat.uniforms;
    g.uW2O.value.copy(w2o);
    g.uInvProj.value.copy(camera.projectionMatrixInverse);
    g.uCamWorld.value.copy(camera.matrixWorld);
    g.uCam.value.copy(camera.position);
    g.uSun.value.copy(sunCam).applyMatrix3(w2o).normalize();
    g.uTime.value = t;
    g.uFrame.value = frame;
    g.uCloudBoost.value = opts.noBoost ? 0 : P.cloudBoost;
    starPts.matrixWorld.identity();

    const globeVisible = P.bgMix < 1;
    renderer.setRenderTarget(globeRT);
    renderer.setClearColor(0x000000, 0);
    renderer.clear();
    if (globeVisible) {
      renderer.autoClear = false;
      renderer.render(starScene, camera);
      renderer.render(globeScene, camera);
      renderer.autoClear = true;
    }

    const useCloud = P.coverage > 0.001;
    if (useCloud) {
      const top = 1.3;
      const camZ = top + 0.4 + 2.0 * P.alt;
      const u = cloudMat.uniforms;
      u.uCamPos.value.set(0.4 + t * 0.05, -0.2, camZ);
      const tilt = rad(lerp(0, 9, smooth(2.4, 3.8, t)));
      const fwd = new THREE.Vector3(0, Math.sin(tilt), -Math.cos(tilt)).normalize();
      let up = new THREE.Vector3(Math.sin(P.roll), Math.cos(P.roll), 0);
      const right = new THREE.Vector3().crossVectors(fwd, up).normalize();
      up = new THREE.Vector3().crossVectors(right, fwd).normalize();
      u.uFwd.value.copy(fwd);
      u.uRight.value.copy(right);
      u.uUp.value.copy(up);
      u.uCoverage.value = P.coverage;
      u.uDensity.value = P.densityBoost;
      u.uPart.value = P.part;
      const reach = (camZ - 0.5) / Math.max(0.2, -fwd.z);
      u.uPartC.value.set(u.uCamPos.value.x + fwd.x * reach, u.uCamPos.value.y + fwd.y * reach);
      u.uTime.value = t;
      u.uFrame.value = frame;
      renderer.setRenderTarget(cloudRT);
      renderer.render(cloudScene, camera);
    }

    const c = compMat.uniforms;
    c.uBgMix.value = P.bgMix;
    c.uWhite.value = P.white;
    c.uFrame.value = frame;
    c.uUseCloud.value = useCloud ? 1 : 0;
    c.uDeckAll.value = smoother(2.4, 2.65, t);
    renderer.setRenderTarget(null);
    renderer.render(compScene, camera);
    renderer.getContext().finish();
    return canvas.toDataURL('image/png');
  }

  return { render, FRAMES, FPS };
}
