// T2 maps-backend (BE-30): read-only load scenario for the map reads and the cached public reads.
// Run it only against a local or TEST server seeded with seed_dense_district.py (see README.md), never production.
//
//   PERF_BASE_URL=http://127.0.0.1:8080 PERF_DRY_RUN=false k6 run ci/perf/maps/k6_map_reads.js
//
// Optional: PERF_TOKEN (an app token of a seeded load-test member, for the signed-in reads: fog cells, deals),
// MAPS_VUS (default 32), MAPS_DURATION (default 60s), MAPS_CENTER_LAT / MAPS_CENTER_LNG (seed defaults).
// Every request is a read; there is no write, payment or callback in this file.
import http from 'k6/http';
import { check, fail } from 'k6';
import { Trend } from 'k6/metrics';

const BASE = __ENV.PERF_BASE_URL;
const TOKEN = __ENV.PERF_TOKEN || '';
const LAT = parseFloat(__ENV.MAPS_CENTER_LAT || '30.2590');
const LNG = parseFloat(__ENV.MAPS_CENTER_LNG || '120.1500');
const BLOCKED = ['chengyinhub.com', 'www.chengyinhub.com', 'api.chengyinhub.com'];

export const options = {
  scenarios: {
    map: {
      executor: 'constant-vus',
      vus: parseInt(__ENV.MAPS_VUS || '32', 10),
      duration: __ENV.MAPS_DURATION || '60s',
    },
  },
  // Same read thresholds as ci/perf/manifest.json (P95 300 ms / P99 800 ms, business errors < 0.5 %).
  thresholds: {
    http_req_duration: ['p(95)<300', 'p(99)<800'],
    checks: ['rate>0.995'],
  },
};

const perRoute = {};
for (const name of ['pois', 'fog', 'deals', 'sponsored', 'clubs', 'features', 'banner', 'category']) {
  perRoute[name] = new Trend(`maps_${name}_ms`, true);
}

export function setup() {
  if (__ENV.PERF_DRY_RUN !== 'false') return { dry: true };
  if (!BASE) fail('PERF_BASE_URL is required');
  const host = BASE.replace(/^https?:\/\//, '').split(/[/:]/)[0].toLowerCase();
  if (BLOCKED.includes(host)) fail(`PRODUCTION_TARGET_BLOCKED: ${host}`);
  return { dry: false };
}

function jitter(span) {
  return (Math.random() - 0.5) * span;
}

function headers(json) {
  const h = {};
  if (TOKEN) h.Authorization = `Bearer ${TOKEN}`;
  if (json) h['Content-Type'] = 'application/json';
  return { headers: h };
}

function ok(res, name) {
  perRoute[name].add(res.timings.duration);
  check(res, { [`${name} 200`]: (r) => r.status === 200 && r.json('code') === 200 });
}

export default function (data) {
  if (data.dry) return;
  const lat = LAT + jitter(0.05);
  const lng = LNG + jitter(0.06);
  const pick = Math.random();
  if (pick < 0.45) {
    // The map pins: the hottest read (a pan or a walk asks again every few seconds).
    ok(http.get(`${BASE}/api/roam/pois?lat=${lat.toFixed(6)}&lng=${lng.toFixed(6)}&radius=3000`, headers(false)), 'pois');
  } else if (pick < 0.55 && TOKEN) {
    ok(http.get(`${BASE}/api/roam/fog/cells?south=${(lat - 0.013).toFixed(6)}&west=${(lng - 0.016).toFixed(6)}&north=${(lat + 0.013).toFixed(6)}&east=${(lng + 0.016).toFixed(6)}`, headers(false)), 'fog');
  } else if (pick < 0.62 && TOKEN) {
    ok(http.get(`${BASE}/api/marketplace/deals?minLat=${(lat - 0.02).toFixed(6)}&maxLat=${(lat + 0.02).toFixed(6)}&minLng=${(lng - 0.025).toFixed(6)}&maxLng=${(lng + 0.025).toFixed(6)}`, headers(false)), 'deals');
  } else if (pick < 0.68) {
    ok(http.get(`${BASE}/api/marketplace/sponsored-quests?minLat=${(lat - 0.02).toFixed(6)}&maxLat=${(lat + 0.02).toFixed(6)}&minLng=${(lng - 0.025).toFixed(6)}&maxLng=${(lng + 0.025).toFixed(6)}`, headers(false)), 'sponsored');
  } else if (pick < 0.75) {
    ok(http.post(`${BASE}/api/club/nearby`, JSON.stringify({ lat, lng, radiusKm: 5, limit: 50 }), headers(true)), 'clubs');
  } else if (pick < 0.87) {
    ok(http.post(`${BASE}/api/config/features`, null, headers(false)), 'features');
  } else if (pick < 0.94) {
    ok(http.post(`${BASE}/api/common/banner?showType=1`, null, headers(false)), 'banner');
  } else {
    ok(http.post(`${BASE}/api/category/list?type=MERCHANT&parentid=0`, null, headers(false)), 'category');
  }
}
