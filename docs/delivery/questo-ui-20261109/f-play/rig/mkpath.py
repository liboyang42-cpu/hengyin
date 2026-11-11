# f-play: 自玩通行证 / 城市定向一整局的步骤(票夹 → 开始游戏 → 两站 → 问答 → 本局完成 → 通关卡 → 完成页 → 继续)
import json, sys
tid, pre = sys.argv[1], sys.argv[2]
P = lambda n: pre + '-' + n
s = [{"wait":1500},{"open":"pages/topic/index/index?id=%s" % tid},{"wait":3500},{"eval":"($P.onHeroCta(),1)"},{"wait":3000},{"shot":P("01-wallet")},
     {"tap":"开始游戏"},{"wait":5000},{"tryTap":["开启定位",{"nth":1}]},{"wait":1500},{"shot":P("02-intro")},{"tap":"开始游戏"},{"wait":1200},
     {"strip":P("03-chapter1"),"n":3,"every":900},{"wait":2500},{"shot":P("04-story")},{"scroll":900},{"wait":1500},{"tryTap":"继续探索"},{"wait":2000},
     {"loc":[31.2304,121.4737]},{"wait":4000},{"shot":P("05-st1-arrived")},{"scroll":900},{"wait":1500},{"shot":P("06-ch1-tail")},{"tryTap":"继续探索"},{"wait":2500},
     {"strip":P("07-chapter2"),"n":3,"every":1200},{"wait":3000},{"scroll":900},{"wait":1500},{"tryTap":"继续探索"},{"wait":2000},
     {"loc":[31.2380,121.4840]},{"wait":3000},{"shot":P("08-st2-card")},{"tryTap":"开始任务"},{"wait":2000},{"tryTap":"开始"},{"wait":2500},
     {"type":[".gp2__input","3"]},{"tap":"提交"},{"wait":3500},{"shot":P("09-ending")},{"tapSel":".pv-ending__go"},{"wait":8000},{"shot":P("10-finale")},
     {"tapSel":".cf__pill--s"},{"wait":3000},{"shot":P("11-finish")},{"tapSel":".finsheet__cta"},{"wait":3500},{"route":1},{"shot":P("12-after-continue")},
     {"eval":"JSON.stringify({route:$P.route,chapterFull:$P.data.chapterFull,paras:($P.data.chapterParas||[]).map(p=>(p.text||p.kind).slice(0,10))})"},
     {"scroll":-600},{"wait":1500},{"shot":P("13-scroll-up")},{"scroll":-3000},{"wait":1500},{"shot":P("14-top")},
     {"eval":"($P.toggleSessionTools(),1)"},{"wait":1500},{"shot":P("15-tools")},{"tryTap":"切换章节"},{"wait":1500},{"shot":P("16-picker")},
     {"eval":"JSON.stringify($P.data.chapterPick)"},{"tapSel":".chpick__row"},{"wait":3500},{"shot":P("17-picked-ch1")},{"dump":1,"max":900}]
json.dump(s, open(pre + '.json', 'w'))
