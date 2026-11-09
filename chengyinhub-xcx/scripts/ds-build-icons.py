#!/usr/bin/env python3
# ds-build-icons.py —— 从 design/icons/coolicons/*.svg 生成 components/cy/icon/icons.wxss
# 方案:CSS mask + data URI(仓库先例:roam 底栏 .ab-ic--route),background-color: currentColor 随主题。
# 图标源:coolicons © Kryston Schwarze,CC BY 4.0；category-* 为项目自绘分类图标。
import glob, os, urllib.parse
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))          # chengyinhub-xcx/
SRC = os.path.join(os.path.dirname(ROOT), 'design', 'icons', 'coolicons')   # 仓库根/design(不进小程序包)
DST = os.path.join(ROOT, 'components', 'cy', 'icon', 'icons.wxss')
# 2026-11-09 V3-30 业主「icon 普遍偏右下角…里面没有居中的感觉」:逐枚渲染量了 alpha 外接框,
# 偏离画布中心 ≥1.5% 的改 viewBox 起点(图形一笔不动,只平移取景),不在使用处补偏移。
# 三角形(play)按质心居中;成对方向箭头(back / chevron-right / slide-arrow)贴边对齐是有意的,不动。
OPTICAL_ORIGIN = {
    'calendar': (0, -1),
    'camera': (0, -0.5),
    'category-food': (-1, 0),
    'category-repair': (0, 0.45),
    'category-travel': (0, -0.5),
    'comment': (0, 1.05),
    'gift': (0, -0.5),
    'gps': (0.45, 0.45),
    'heart-filled': (0, 0.5),
    'heart': (0, 0.5),
    'mtab-customers': (0, 0.5),
    'poi-shop': (1, -0.5),
    'tab-club': (0, 0.5),
    'tab-feed': (0, 0.5),
    'turn-right': (0, 0.75),
}
import re
rules, names = [], []
for f in sorted(glob.glob(f'{SRC}/*.svg')):
    sem = os.path.basename(f)[:-4]
    svg = open(f).read()
    if sem in OPTICAL_ORIGIN:
        svg = re.sub(r'viewBox="0 0 ', 'viewBox="%s %s ' % OPTICAL_ORIGIN[sem], svg, count=1)
    uri = urllib.parse.quote(svg, safe="~()*!.'")
    # URI 只存一次进 CSS 变量,基类经 var() 双前缀引用 —— icons.wxss 体积减半
    rules.append(f'.cyi--{sem} {{ --cyi-m: url("data:image/svg+xml,{uri}"); }}')
    names.append(sem)
header = ('/* 自动生成,勿手改 —— python3 scripts/ds-build-icons.py\n'
          f' * 源:design/icons/coolicons/(coolicons © Kryston Schwarze, CC BY 4.0; category-* 项目自绘)\n'
          f' * 图标 {len(names)} 枚:{" ".join(names)} */\n')
open(DST, 'w').write(header + '\n'.join(rules) + '\n')
print(f'OK {len(names)} icons -> {DST} ({os.path.getsize(DST)} bytes)')
