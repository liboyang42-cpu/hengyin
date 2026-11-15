'use strict';
// D-26:开卡包的蓄力 / 撕包 / 发牌共 3 秒以上,播放中再点一下直接落定。拖拽撕口(tearPct / packGrab)不算播放中,不接管。
const GUARD_MS = 300;   // 刚点开就连点的第二下不算跳过

function isPackPlaying(d) {
  return !!d && !d.packOpen && !!(d.packShake || d.packTear || d.packBurst || d.packSink);
}

function canSkipPack(d, startedAt, now) {
  return isPackPlaying(d) && Number(now) - Number(startedAt || 0) >= GUARD_MS;
}

/** 落定终态:包已移除、通行证头出现、所有爆炸层收掉 */
function packSettledPatch() {
  return { packOpen: true, headIn: true, packShake: false, packDim: false, packTear: false, packBurst: false,
    packSink: false, blastRays: [], blastShards: [] };
}

module.exports = { GUARD_MS, isPackPlaying, canSkipPack, packSettledPatch };
