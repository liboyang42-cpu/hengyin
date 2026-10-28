function circle(ctx, size, radius, fill, stroke, width) {
  ctx.beginPath()
  ctx.arc(size / 2, size / 2, radius, 0, Math.PI * 2)
  if (fill) {
    ctx.fillStyle = fill
    ctx.fill()
  }
  if (stroke) {
    ctx.strokeStyle = stroke
    ctx.lineWidth = width || 6
    ctx.stroke()
  }
}

function drawPlayStateMarker(ctx, size, stateKey) {
  const state = stateKey || 'actionable'
  const mid = size / 2
  ctx.clearRect(0, 0, size, size)

  if (state === 'target') {
    circle(ctx, size, 42, '#111111')
    circle(ctx, size, 23, '#FFFFFF')
    circle(ctx, size, 9, '#111111')
    return
  }
  if (state === 'actionable') {
    circle(ctx, size, 38, '#FFFFFF', '#111111', 7)
    return
  }
  if (state === 'paused') {
    circle(ctx, size, 38, '#4A4A4A', '#FFFFFF', 4)
    ctx.fillStyle = '#FFFFFF'
    ctx.fillRect(mid - 14, mid - 20, 9, 40)
    ctx.fillRect(mid + 5, mid - 20, 9, 40)
    return
  }
  if (state === 'completed') {
    circle(ctx, size, 38, '#111111')
    ctx.strokeStyle = '#FFFFFF'
    ctx.lineWidth = 7
    ctx.beginPath()
    ctx.moveTo(mid - 18, mid)
    ctx.lineTo(mid - 5, mid + 14)
    ctx.lineTo(mid + 22, mid - 17)
    ctx.stroke()
    return
  }
  /* CU-M-54 待核销:打卡那一步已经完成(黑底白勾同 completed),外面再套一圈白色虚线 ——
     「还差一步」:券要商家扫码才到手。不跟 completed 长得一样,否则地图上分不出哪几个还没领。 */
  if (state === 'redeem-pending') {
    circle(ctx, size, 38, '#111111')
    ctx.strokeStyle = '#FFFFFF'   /* ds-ok 与同文件既有 marker 描边/勾同值;canvas 拿不到 var() */
    ctx.lineWidth = 7
    ctx.beginPath()
    ctx.moveTo(mid - 18, mid)
    ctx.lineTo(mid - 5, mid + 14)
    ctx.lineTo(mid + 22, mid - 17)
    ctx.stroke()
    if (ctx.setLineDash) ctx.setLineDash([9, 7])
    circle(ctx, size, 47, null, '#FFFFFF', 5)
    if (ctx.setLineDash) ctx.setLineDash([])
    return
  }
  if (state === 'candidate') {
    circle(ctx, size, 38, '#FFFFFF')
    if (ctx.setLineDash) ctx.setLineDash([10, 8])
    circle(ctx, size, 38, null, '#555555', 6)
    if (ctx.setLineDash) ctx.setLineDash([])
    return
  }
  if (state === 'restricted') {
    circle(ctx, size, 38, '#4A4A4A', '#FFFFFF', 4)
    ctx.strokeStyle = '#FFFFFF'
    ctx.lineWidth = 5
    ctx.beginPath()
    ctx.arc(mid, mid - 7, 13, Math.PI, 0)
    ctx.stroke()
    ctx.fillStyle = '#FFFFFF'
    ctx.fillRect(mid - 18, mid - 7, 36, 29)
    ctx.fillStyle = '#4A4A4A'
    ctx.fillRect(mid - 3, mid + 3, 6, 10)
    return
  }
  if (state === 'location-off') {
    circle(ctx, size, 38, '#FFFFFF', '#111111', 7)
    ctx.strokeStyle = '#111111'
    ctx.lineWidth = 6
    ctx.beginPath()
    ctx.moveTo(mid, mid - 26)
    ctx.lineTo(mid - 18, mid + 5)
    ctx.lineTo(mid, mid + 26)
    ctx.lineTo(mid + 18, mid + 5)
    ctx.closePath()
    ctx.stroke()
    ctx.beginPath()
    ctx.moveTo(mid - 23, mid + 25)
    ctx.lineTo(mid + 23, mid - 21)
    ctx.stroke()
    return
  }
  if (state === 'offline') {
    circle(ctx, size, 38, '#FFFFFF')
    if (ctx.setLineDash) ctx.setLineDash([8, 7])
    circle(ctx, size, 38, null, '#111111', 6)
    if (ctx.setLineDash) ctx.setLineDash([])
    ctx.strokeStyle = '#111111'
    ctx.lineWidth = 6
    ctx.beginPath()
    ctx.moveTo(mid - 20, mid)
    ctx.lineTo(mid - 7, mid)
    ctx.moveTo(mid + 7, mid)
    ctx.lineTo(mid + 20, mid)
    ctx.stroke()
    return
  }

  circle(ctx, size, 38, '#FFFFFF', '#111111', 7)
  ctx.strokeStyle = '#111111'
  ctx.lineWidth = 8
  ctx.beginPath()
  ctx.moveTo(mid, mid - 22)
  ctx.lineTo(mid, mid + 5)
  ctx.stroke()
  circle(ctx, size, 5, '#111111')
}

/* 带序号的点位(2026-10-27 用户:「地图点位样式要统一」)。
   以前是「图标 + 头上一块深色序号胶囊」两件套,站点一挤胶囊就互相叠、序号飘在别的点上。
   现在序号画进圆里,状态只换底色:待去 = 白底黑字,已完成 = 黑底白字 + 右下小勾,未解锁 = 灰底 + 右下小锁。 */
function drawNumberedMarker(ctx, size, stateKey, num) {
  const state = stateKey || 'actionable'
  const mid = size / 2
  const r = 36
  ctx.clearRect(0, 0, size, size)
  let fill = '#FFFFFF'   /* ds-ok canvas 拿不到 var()，与同文件 marker 同值 */
  let ink = '#111111'
  if (state === 'completed' || state === 'redeem-pending') { fill = '#111111'; ink = '#FFFFFF' }   /* ds-ok */
  else if (state === 'restricted' || state === 'paused') { fill = '#4A4A4A'; ink = '#FFFFFF' }   /* ds-ok */
  if (state === 'candidate') {
    circle(ctx, size, r, fill)
    if (ctx.setLineDash) ctx.setLineDash([10, 8])
    circle(ctx, size, r, null, '#555555', 6)
    if (ctx.setLineDash) ctx.setLineDash([])
  } else {
    circle(ctx, size, r, fill, fill === '#FFFFFF' ? '#111111' : '#FFFFFF', fill === '#FFFFFF' ? 7 : 5)   /* ds-ok */
  }
  const label = String(num)
  ctx.fillStyle = ink
  ctx.font = 'bold ' + (label.length > 2 ? 30 : 40) + 'px sans-serif'
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.fillText(label, mid, mid + 2)
  if (state === 'completed' || state === 'redeem-pending' || state === 'restricted') {
    const bx = mid + 32
    const by = mid + 32
    ctx.beginPath()
    ctx.arc(bx, by, 17, 0, Math.PI * 2)
    ctx.fillStyle = '#FFFFFF'   /* ds-ok */
    ctx.fill()
    ctx.strokeStyle = '#111111'   /* ds-ok */
    ctx.fillStyle = '#111111'   /* ds-ok */
    if (state === 'restricted') {
      ctx.lineWidth = 3
      ctx.beginPath()
      ctx.arc(bx, by - 4, 6, Math.PI, 0)
      ctx.stroke()
      ctx.fillRect(bx - 8, by - 4, 16, 12)
    } else {
      ctx.lineWidth = 4
      ctx.beginPath()
      ctx.moveTo(bx - 8, by)
      ctx.lineTo(bx - 2, by + 6)
      ctx.lineTo(bx + 9, by - 6)
      ctx.stroke()
    }
  }
}

module.exports = { drawPlayStateMarker, drawNumberedMarker }
