// V-13(2026-10-29):采用模板被媒体类 422 拒绝时,只有回包点名「封面」才清封面。
// 官方模板封面由后端承认,拒绝点的是别的媒体(或没点名)时封面原样保留,
// 不能把一张后端接受的封面清成「原封面暂不可用」。
// 点名口径(与后端 lane 约定):data.field === 'imgUrl',或 data.mediaUrl 就是这张封面。
function rejectionNamesCover(res, cover) {
  const data = res && res.data;
  if (!data || typeof data !== 'object') return false;
  if (data.field === 'imgUrl') return true;
  return !!cover && !!data.mediaUrl && String(data.mediaUrl) === String(cover);
}

module.exports = { rejectionNamesCover: rejectionNamesCover };
