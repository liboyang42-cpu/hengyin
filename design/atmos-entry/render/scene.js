import * as THREE from 'three';
import { paintSet } from './textures.js';
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

// Dive progress: slow drift, then an accelerating fall whose speed peaks inside the clouds.
function diveEase(t) {
  const x = clamp01(t / 3.05);
  // mostly ease-in with a soft start
  return x < 1 ? Math.pow(x, 2.6) * (1.0 - 0.18 * Math.sin(Math.PI * x)) : 1;
}

export const CHINA = { lon: 104.5, lat: 32.5 };
export const CUT = 2.7;

export function params(t) {
  const alt = Math.exp(lerp(Math.log(5.6), Math.log(0.06), diveEase(t)));
  const turn = smoother(0.0, 2.55, t);
  return {
    t,
    alt,
    lon: lerp(168, CHINA.lon, turn),
    lat: lerp(10, CHINA.lat, turn),
    lookY: lerp(0.95, 0.0, smoother(0.2, 2.0, t)),
    roll: rad(lerp(-7, 0, smoother(0.0, 2.6, t))),
    globeClouds: lerp(0.55, 0.85, smooth(1.2, 2.4, t)),
    coverage: lerp(0.0, 1.0, smoother(1.75, 2.62, t)),
    densityBoost: 1 + 3.5 * Math.exp(-(((t - CUT) / 0.09) ** 2)),
    bgMix: t >= CUT ? 1 : 0,
    descend: 2.05 * smoother(2.35, 3.62, t),
    part: smoother(2.95, 3.72, t),
    white: smooth(3.42, 3.8, t),
    black: 1 - smooth(0.0, 0.32, t),
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

const starsFrag = /* glsl */ `
  ${COMMON}
  precision highp sampler3D;
  in vec2 vUv;
  uniform mat4 uInvProj; uniform mat4 uCamWorld; uniform vec2 uRes; uniform sampler3D tShape;
  out vec4 outColor;
  vec3 starLayer(vec3 d, float scale, float prob, float pix){
    vec3 g = d * scale; vec3 cell = floor(g); vec3 acc = vec3(0.0);
    for(int k=-1;k<=1;k++) for(int j=-1;j<=1;j++) for(int i=-1;i<=1;i++){
      vec3 c = cell + vec3(i,j,k);
      vec3 h = hash33(c + 17.0);
      if(h.x > prob) continue;
      vec3 sp = normalize((c + 0.2 + 0.6 * hash33(c * 1.7 + 3.1)) / scale);
      float ang = length(d - sp);
      float sigma = pix * 0.75;
      float m = pow(h.y, 6.0) * 3.0 + 0.25;
      vec3 tint = mix(vec3(0.75, 0.82, 1.0), vec3(1.0, 0.86, 0.72), h.z);
      acc += tint * m * exp(-ang * ang / (2.0 * sigma * sigma));
    }
    return acc;
  }
  void main(){
    vec4 ndc = vec4(vUv * 2.0 - 1.0, 1.0, 1.0);
    vec4 v = uInvProj * ndc; v /= v.w;
    vec3 d = normalize((uCamWorld * vec4(normalize(v.xyz), 0.0)).xyz);
    float pix = 2.0 * tan(radians(25.0)) / uRes.y;
    vec3 col = vec3(0.0);
    col += starLayer(d, 90.0, 0.06, pix) * 0.9;
    col += starLayer(d, 200.0, 0.05, pix) * 0.45;
    col += starLayer(d, 420.0, 0.04, pix) * 0.22;
    // faint nebula haze, lilac and blue
    float n1 = texture(tShape, d * 0.35 + 0.2).r;
    float n2 = texture(tShape, d * 0.8 + 0.6).g;
    float band = exp(-pow(dot(d, normalize(vec3(0.35, 0.9, -0.25))) * 2.6, 2.0));
    col += vec3(0.30, 0.22, 0.48) * pow(n1, 3.0) * 0.08 * (0.4 + band);
    col += vec3(0.12, 0.20, 0.42) * pow(n2, 4.0) * 0.06 * band;
    col += vec3(0.010, 0.012, 0.028);
    outColor = vec4(col, 1.0);
  }
`;

const earthVert = /* glsl */ `
  out vec3 vObj; out vec3 vWorld; out vec3 vNormalW;
  void main(){
    vObj = position;
    vec4 w = modelMatrix * vec4(position, 1.0);
    vWorld = w.xyz;
    vNormalW = normalize(mat3(modelMatrix) * normal);
    gl_Position = projectionMatrix * viewMatrix * w;
  }
`;

const earthFrag = /* glsl */ `
  ${COMMON}
  precision highp sampler3D;
  in vec3 vObj; in vec3 vWorld; in vec3 vNormalW;
  uniform sampler2D tDay, tNight, tHeight, tMask, tDayC, tNightC, tHeightC, tMaskC;
  uniform vec4 uWin; // lon0, lon1, lat0, lat1 (radians)
  uniform sampler3D tShape;
  uniform vec3 uSun; uniform vec3 uCam; uniform float uClouds; uniform float uTime; uniform mat3 uObjToWorld;
  out vec4 outColor;

  struct S { vec3 day; vec3 night; float h; float land; };

  S sampleWorld(vec2 uv){
    // seam-safe gradients (wrap at lon = 180)
    vec2 uvB = vec2(fract(uv.x + 0.5) - 0.5, uv.y);
    vec2 dxA = dFdx(uv), dyA = dFdy(uv), dxB = dFdx(uvB), dyB = dFdy(uvB);
    vec2 dx = abs(dxA.x) < abs(dxB.x) ? dxA : dxB;
    vec2 dy = abs(dyA.x) < abs(dyB.x) ? dyA : dyB;
    S s;
    s.day = textureGrad(tDay, uv, dx, dy).rgb;
    s.night = textureGrad(tNight, uv, dx, dy).rgb;
    s.h = textureGrad(tHeight, uv, dx, dy).r;
    s.land = textureGrad(tMask, uv, dx, dy).r;
    return s;
  }

  void main(){
    vec3 p = normalize(vObj);
    float lat = asin(clamp(p.y, -1.0, 1.0));
    float lon = atan(p.x, p.z);
    vec2 uv = vec2((lon + PI) / (2.0 * PI), (lat + 0.5 * PI) / PI);
    S s = sampleWorld(uv);

    // detailed window over China, blended at its edges
    vec2 cuv = vec2((lon - uWin.x) / (uWin.y - uWin.x), (lat - uWin.z) / (uWin.w - uWin.z));
    float cw = smoothstep(0.0, 0.08, cuv.x) * smoothstep(1.0, 0.92, cuv.x) * smoothstep(0.0, 0.1, cuv.y) * smoothstep(1.0, 0.9, cuv.y);
    if (cw > 0.0) {
      vec2 dx = dFdx(cuv), dy = dFdy(cuv);
      s.day = mix(s.day, textureGrad(tDayC, cuv, dx, dy).rgb, cw);
      s.night = mix(s.night, textureGrad(tNightC, cuv, dx, dy).rgb, cw);
      s.h = mix(s.h, textureGrad(tHeightC, cuv, dx, dy).r, cw);
      s.land = mix(s.land, textureGrad(tMaskC, cuv, dx, dy).r, cw);
    }

    vec3 N0 = normalize(vNormalW);
    // derivative bump mapping
    vec3 dPdx = dFdx(vWorld), dPdy = dFdy(vWorld);
    float dhx = dFdx(s.h), dhy = dFdy(s.h);
    vec3 r1 = cross(dPdy, N0), r2 = cross(N0, dPdx);
    float det = dot(dPdx, r1);
    vec3 grad = sign(det) * (dhx * r1 + dhy * r2);
    vec3 N = normalize(abs(det) * N0 - 0.012 * grad);

    vec3 V = normalize(uCam - vWorld);
    float ndl0 = dot(N0, uSun);
    float ndl = dot(N, uSun);

    // stylised soft day light with a warm terminator
    float dayL = smoothstep(-0.10, 0.55, ndl);
    vec3 warm = mix(vec3(1.0, 0.52, 0.28), vec3(1.0, 0.96, 0.9), smoothstep(0.0, 0.4, ndl0));
    vec3 col = s.day * dayL * warm * 1.25;

    // ocean glint
    float ocean = 1.0 - s.land;
    vec3 H = normalize(uSun + V);
    col += vec3(1.0, 0.85, 0.65) * pow(max(dot(N0, H), 0.0), 48.0) * 0.45 * ocean * smoothstep(0.0, 0.2, ndl0);

    // clouds on the planet (shadowed surface underneath)
    vec3 q = p * 1.35 + vec3(uTime * 0.004, 0.0, 0.0);
    vec4 cn = texture(tShape, q);
    float wf = cn.g * 0.625 + cn.b * 0.25 + cn.a * 0.125;
    float base = clamp((cn.r - (wf - 1.0)) / (2.0 - wf), 0.0, 1.0);
    float cov = smoothstep(1.0 - uClouds * 0.62, 1.0 - uClouds * 0.18, base);
    vec4 cs = texture(tShape, (p + uSun * 0.02) * 1.35 + vec3(uTime * 0.004, 0.0, 0.0));
    float csh = smoothstep(1.0 - uClouds * 0.62, 1.0, clamp((cs.r - (cs.g * 0.625 + cs.b * 0.25 + cs.a * 0.125 - 1.0)), 0.0, 1.0));
    col *= 1.0 - 0.35 * csh;

    // night lights fade in on the dark side
    float nightW = 1.0 - smoothstep(-0.22, 0.06, ndl0);
    col += s.night * nightW * 1.6 * (1.0 - cov * 0.75);

    vec3 cloudCol = mix(vec3(0.42, 0.40, 0.60), vec3(1.0, 0.97, 0.95), smoothstep(-0.05, 0.5, ndl0)) * warm;
    cloudCol *= smoothstep(-0.25, 0.2, ndl0) * 1.15 + 0.02;
    col = mix(col, cloudCol, cov * 0.92);

    // limb: blue rim on the lit side, faint lilac on the dark side
    float fres = pow(1.0 - max(dot(N0, V), 0.0), 3.0);
    col += vec3(0.25, 0.55, 1.0) * fres * 0.9 * smoothstep(-0.35, 0.5, ndl0);
    col += vec3(0.28, 0.22, 0.45) * fres * 0.25;

    outColor = vec4(col, 1.0);
  }
`;

const atmoVert = /* glsl */ `
  out vec3 vWorld;
  void main(){ vec4 w = modelMatrix * vec4(position, 1.0); vWorld = w.xyz; gl_Position = projectionMatrix * viewMatrix * w; }
`;

const atmoFrag = /* glsl */ `
  ${COMMON}
  in vec3 vWorld;
  uniform vec3 uCam; uniform vec3 uSun; uniform float uRa; uniform float uHs;
  out vec4 outColor;
  vec2 sph(vec3 ro, vec3 rd, float r){ float b = dot(ro, rd); float c = dot(ro, ro) - r * r; float h = b * b - c; if (h < 0.0) return vec2(1e9, -1e9); h = sqrt(h); return vec2(-b - h, -b + h); }
  void main(){
    vec3 ro = uCam; vec3 rd = normalize(vWorld - uCam);
    vec2 ta = sph(ro, rd, uRa);
    vec2 tp = sph(ro, rd, 1.0);
    float t0 = max(ta.x, 0.0);
    float t1 = tp.x > 0.0 && tp.x < 1e8 ? tp.x : ta.y;
    if (t1 <= t0) discard;
    const int N = 20;
    float ds = (t1 - t0) / float(N);
    vec3 beta = vec3(0.16, 0.42, 1.0);
    vec3 acc = vec3(0.0); float od = 0.0; float mie = 0.0;
    float mu = dot(rd, uSun);
    for (int i = 0; i < N; i++) {
      vec3 p = ro + rd * (t0 + ds * (float(i) + 0.5));
      float r = length(p);
      float dens = exp(-(r - 1.0) / uHs);
      od += dens * ds;
      float sunVis = smoothstep(-0.18, 0.28, dot(p / r, uSun));
      vec3 tr = exp(-beta * od * 26.0);
      acc += dens * ds * sunVis * tr;
      mie += dens * ds * sunVis * tr.g;
    }
    float phaseR = 0.75 * (1.0 + mu * mu);
    float g = 0.76; float phaseM = (1.0 - g * g) / pow(1.0 + g * g - 2.0 * g * mu, 1.5) * 0.08;
    vec3 col = acc * beta * phaseR * 26.0 + vec3(1.0, 0.8, 0.62) * mie * phaseM * 26.0;
    // a little lilac in the deep limb
    col += vec3(0.22, 0.14, 0.36) * acc.g * 6.0 * (1.0 - smoothstep(-0.2, 0.3, mu));
    outColor = vec4(col, 1.0);
  }
`;

const cloudFrag = /* glsl */ `
  ${COMMON}
  precision highp sampler3D;
  in vec2 vUv;
  uniform sampler3D tShape, tDetail;
  uniform vec3 uCamPos, uFwd, uRight, uUp; uniform float uTanY, uAspect;
  uniform float uCoverage, uDensity, uPart, uTime; uniform vec2 uRes; uniform float uFrame;
  uniform vec3 uSun;
  out vec4 outColor;
  const float TOP = 1.3;
  const float SCALE = 0.30;

  float remap(float v, float a, float b, float c, float d){ return c + (v - a) / (b - a) * (d - c); }

  float density(vec3 p, bool detail){
    float h = clamp(p.z / TOP, 0.0, 1.0);
    float prof = smoothstep(0.0, 0.22, h) * smoothstep(1.0, 0.55, h);
    vec3 q = p * SCALE + vec3(uTime * 0.018, uTime * 0.006, 0.0);
    vec4 s = texture(tShape, q);
    float wf = s.g * 0.625 + s.b * 0.25 + s.a * 0.125;
    float base = remap(s.r, wf - 1.0, 1.0, 0.0, 1.0) * prof;
    float cov = uCoverage;
    float d = clamp(remap(base, 1.0 - cov, 1.0, 0.0, 1.0), 0.0, 1.0);
    // parting: an opening that grows outward around the descent axis, ragged by noise
    if (uPart > 0.0) {
      float r = length(p.xy - uCamPos.xy) + (s.g - 0.5) * 0.9;
      float R = uPart * 5.0;
      d *= smoothstep(R - 0.2, R + 1.4, r);
    }
    if (d <= 0.0 || !detail) return d * uDensity;
    vec4 dt = texture(tDetail, q * 3.1 + vec3(0.0, 0.0, uTime * 0.02));
    float df = dt.r * 0.625 + dt.g * 0.25 + dt.b * 0.125;
    df = mix(df, 1.0 - df, smoothstep(0.1, 0.6, h));
    d = clamp(remap(d, df * 0.32, 1.0, 0.0, 1.0), 0.0, 1.0);
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
    vec3 sunCol = vec3(1.05, 1.0, 0.97) * 2.4;
    vec3 shadowCol = vec3(0.50, 0.46, 0.70);
    vec3 skyCol = vec3(0.86, 0.84, 1.0);
    const float SIGMA = 9.0;
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
        vec3 amb = mix(shadowCol, skyCol, smoothstep(0.0, 0.9, h)) * 0.95;
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
  uniform sampler2D tGlobe, tCloud; uniform float uBgMix, uWhite, uBlack, uFrame; uniform vec2 uRes; uniform float uUseCloud;
  out vec4 outColor;
  vec3 aces(vec3 x){ return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0); }
  vec3 toSRGB(vec3 c){ return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c)); }
  void main(){
    vec3 bg = texture(tGlobe, vUv).rgb;
    vec3 haze = mix(vec3(0.80, 0.77, 0.92), vec3(0.98, 0.96, 1.02), smoothstep(0.0, 1.0, vUv.y)) * 1.15;
    haze += vec3(0.25, 0.22, 0.18) * exp(-pow(length((vUv - vec2(0.75, 0.85)) * vec2(1.0, 0.6)) * 2.2, 2.0));
    bg = mix(bg, haze, uBgMix);
    vec3 col = bg;
    if (uUseCloud > 0.5) { vec4 c = texture(tCloud, vUv); col = bg * c.a + c.rgb; }
    col = aces(col * 0.95);
    col = toSRGB(col);
    float vig = smoothstep(1.25, 0.45, length((vUv - 0.5) * vec2(1.0, 0.75)));
    col *= mix(1.0, vig, 0.35 * (1.0 - uWhite));
    col = mix(col, vec3(0.955, 0.945, 0.975), uWhite);
    col *= 1.0 - uBlack;
    // triangular dither against banding
    float n = hash12(gl_FragCoord.xy + uFrame * 37.0) + hash12(gl_FragCoord.xy * 1.37 + uFrame * 11.0) - 1.0;
    col += n / 255.0 * 1.2;
    outColor = vec4(col, 1.0);
  }
`;

// ---------- setup ----------
function canvasTex(c, srgb, mip = true) {
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = srgb ? THREE.SRGBColorSpace : THREE.NoColorSpace;
  t.wrapS = THREE.RepeatWrapping;
  t.wrapT = THREE.ClampToEdgeWrapping;
  t.anisotropy = 8;
  t.generateMipmaps = mip;
  t.minFilter = mip ? THREE.LinearMipmapLinearFilter : THREE.LinearFilter;
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

  log('painting textures');
  const world = paintSet(data, { lon0: -180, lon1: 180, lat0: -90, lat1: 90, w: opts.texW || 4096, h: (opts.texW || 4096) / 2 }, 1);
  const win = { lon0: 72, lon1: 138, lat0: 14, lat1: 54, w: opts.texW || 4096, h: Math.round(((opts.texW || 4096) * 40) / 66) };
  const china = paintSet(data, win, 101);
  if (opts.dumpTextures) opts.dumpTextures({ world, china });
  log('noise volumes');
  const shape = volTex(shapeVolume(opts.noiseN || 96), opts.noiseN || 96);
  const detail = volTex(detailVolume(32), 32);

  const rtOpts = { type: THREE.HalfFloatType, format: THREE.RGBAFormat, depthBuffer: true, minFilter: THREE.LinearFilter, magFilter: THREE.LinearFilter };
  const globeRT = new THREE.WebGLRenderTarget(W, H, { ...rtOpts, samples: 4 });
  const cloudRT = new THREE.WebGLRenderTarget(Math.round(W * cloudScale), Math.round(H * cloudScale), { ...rtOpts, depthBuffer: false });

  const camera = new THREE.PerspectiveCamera(50, W / H, 0.005, 100);
  const quad = new THREE.PlaneGeometry(2, 2);

  // stars background
  const starsMat = new THREE.ShaderMaterial({
    glslVersion: THREE.GLSL3, vertexShader: fsQuadVert, fragmentShader: starsFrag, depthWrite: false, depthTest: false,
    uniforms: { uInvProj: { value: new THREE.Matrix4() }, uCamWorld: { value: new THREE.Matrix4() }, uRes: { value: new THREE.Vector2(W, H) }, tShape: { value: shape } },
  });
  const bgScene = new THREE.Scene();
  const bgMesh = new THREE.Mesh(quad, starsMat);
  bgMesh.frustumCulled = false;
  bgScene.add(bgMesh);

  const sun = new THREE.Vector3(0.86, 0.28, 0.42).normalize();
  const scene = new THREE.Scene();
  const earthGroup = new THREE.Group();
  scene.add(earthGroup);
  const earthMat = new THREE.ShaderMaterial({
    glslVersion: THREE.GLSL3, vertexShader: earthVert, fragmentShader: earthFrag,
    uniforms: {
      tDay: { value: canvasTex(world.day, true) }, tNight: { value: canvasTex(world.night, true) },
      tHeight: { value: canvasTex(world.height, false) }, tMask: { value: canvasTex(world.mask, false) },
      tDayC: { value: canvasTex(china.day, true) }, tNightC: { value: canvasTex(china.night, true) },
      tHeightC: { value: canvasTex(china.height, false) }, tMaskC: { value: canvasTex(china.mask, false) },
      uWin: { value: new THREE.Vector4(rad(win.lon0), rad(win.lon1), rad(win.lat0), rad(win.lat1)) },
      tShape: { value: shape }, uSun: { value: sun }, uCam: { value: new THREE.Vector3() }, uClouds: { value: 0.6 }, uTime: { value: 0 },
    },
  });
  for (const k of ['tDayC', 'tNightC', 'tHeightC', 'tMaskC']) earthMat.uniforms[k].value.wrapS = THREE.ClampToEdgeWrapping;
  const earth = new THREE.Mesh(new THREE.SphereGeometry(1, 384, 192), earthMat);
  earthGroup.add(earth);
  const atmoMat = new THREE.ShaderMaterial({
    glslVersion: THREE.GLSL3, vertexShader: atmoVert, fragmentShader: atmoFrag, transparent: true, depthWrite: false, depthTest: false,
    blending: THREE.AdditiveBlending,
    uniforms: { uCam: { value: new THREE.Vector3() }, uSun: { value: sun }, uRa: { value: 1.05 }, uHs: { value: 0.011 } },
  });
  const atmo = new THREE.Mesh(new THREE.SphereGeometry(1.05, 192, 96), atmoMat);
  atmo.renderOrder = 2;
  scene.add(atmo);

  const cloudMat = new THREE.ShaderMaterial({
    glslVersion: THREE.GLSL3, vertexShader: fsQuadVert, fragmentShader: cloudFrag, depthWrite: false, depthTest: false,
    uniforms: {
      tShape: { value: shape }, tDetail: { value: detail },
      uCamPos: { value: new THREE.Vector3() }, uFwd: { value: new THREE.Vector3() }, uRight: { value: new THREE.Vector3() }, uUp: { value: new THREE.Vector3() },
      uTanY: { value: Math.tan(rad(25)) }, uAspect: { value: W / H },
      uCoverage: { value: 0 }, uDensity: { value: 1 }, uPart: { value: 0 }, uTime: { value: 0 }, uRes: { value: new THREE.Vector2(cloudRT.width, cloudRT.height) }, uFrame: { value: 0 },
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
      tGlobe: { value: globeRT.texture }, tCloud: { value: cloudRT.texture }, uBgMix: { value: 0 }, uWhite: { value: 0 }, uBlack: { value: 0 },
      uFrame: { value: 0 }, uRes: { value: new THREE.Vector2(W, H) }, uUseCloud: { value: 0 },
    },
  });
  const compScene = new THREE.Scene();
  const qm = new THREE.Mesh(quad, compMat);
  qm.frustumCulled = false;
  compScene.add(qm);

  function render(frame) {
    const t = frame / FPS;
    const P = params(t);

    // globe camera
    camera.position.set(0, 0, 1 + P.alt);
    camera.up.set(Math.sin(P.roll), Math.cos(P.roll), 0);
    camera.lookAt(0, P.lookY * (P.alt / 5.6), 0);
    camera.updateMatrixWorld();
    earthGroup.rotation.set(0, 0, 0);
    earthGroup.rotateX(rad(P.lat));
    earthGroup.rotateY(-rad(P.lon));
    earthGroup.updateMatrixWorld(true);
    earthMat.uniforms.uCam.value.copy(camera.position);
    earthMat.uniforms.uClouds.value = P.globeClouds;
    earthMat.uniforms.uTime.value = t;
    atmoMat.uniforms.uCam.value.copy(camera.position);
    starsMat.uniforms.uInvProj.value.copy(camera.projectionMatrixInverse);
    starsMat.uniforms.uCamWorld.value.copy(camera.matrixWorld);

    const globeVisible = P.bgMix < 1;
    renderer.setRenderTarget(globeRT);
    renderer.clear();
    if (globeVisible) {
      renderer.autoClear = false;
      renderer.render(bgScene, camera);
      renderer.clearDepth();
      renderer.render(scene, camera);
      renderer.autoClear = true;
    }

    const useCloud = P.coverage > 0.001;
    if (useCloud) {
      const top = 1.3;
      const camZ = top + 1.25 * P.alt - P.descend;
      const u = cloudMat.uniforms;
      u.uCamPos.value.set(0.4 + t * 0.05, -0.2, camZ);
      const tilt = rad(lerp(0, 9, smooth(2.4, 3.8, t)));
      const fwd = new THREE.Vector3(0, Math.sin(tilt), -Math.cos(tilt)).normalize();
      // screen up in cloud world matches the globe camera (+y), rolled the same way
      let up = new THREE.Vector3(Math.sin(P.roll), Math.cos(P.roll), 0);
      const right = new THREE.Vector3().crossVectors(fwd, up).normalize();
      up = new THREE.Vector3().crossVectors(right, fwd).normalize();
      u.uFwd.value.copy(fwd);
      u.uRight.value.copy(right);
      u.uUp.value.copy(up);
      u.uCoverage.value = P.coverage;
      u.uDensity.value = P.densityBoost;
      u.uPart.value = P.part;
      u.uTime.value = t;
      u.uFrame.value = frame;
      renderer.setRenderTarget(cloudRT);
      renderer.render(cloudScene, camera);
    }

    const c = compMat.uniforms;
    c.uBgMix.value = P.bgMix;
    c.uWhite.value = P.white;
    c.uBlack.value = P.black;
    c.uFrame.value = frame;
    c.uUseCloud.value = useCloud ? 1 : 0;
    renderer.setRenderTarget(null);
    renderer.render(compScene, camera);
    renderer.getContext().finish();
    return canvas.toDataURL('image/png');
  }

  return { render, FRAMES, FPS };
}
