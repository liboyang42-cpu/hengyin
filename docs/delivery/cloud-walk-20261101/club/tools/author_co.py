import sys; sys.path.insert(0, '/root/sc')
from common import *
tok, me = login('walk-club-lead'); print('lead', me.get('id'))
st = cl.http("POST", "/api/publisher/identity/status", token=tok, json_body={})
print('identity status', J(st))
if not (st.get('data') or {}).get('registered'):
    print('register', J(cl.http("POST", "/api/publisher/identity", token=tok, json_body={"realName": "林小舟", "idCard": idcard('31010419900307002'), "consent": True, "source": "topic_publish"})))
G = [
 # (title, vm, question, answer, choices, correct, hint1, hint2, reveal, feedback)
 ("门楣上的年份", 1, "抬头看外滩源壹号正门上方的石刻，上面刻着哪一年？（四位数字）", "1873", None, None, "在正门拱券的正上方。", "数字以 18 开头。", "1873，这座楼重建落成的那一年。", "电报员当年也是从这扇门进出的。"),
 ("真光大楼的楼层", 3, "真光大楼临圆明园路一侧，地面以上一共有几层？", None, ("8 层","9 层","10 层","11 层"), "B", "从街对面数更清楚。", "顶上那层退台也要算。", "9 层，装饰艺术风格。", "数对了，信使就住在第六层。"),
 ("光陆大楼的转角", 2, "拍一张光陆大楼转角的弧形立面，让门牌号进入画面。", None, None, None, "站到虎丘路和南苏州路的路口。", "门牌在转角右手边。", "转角弧面 + 门牌号即可。", "照片已经寄给‘收报人’了。"),
 ("桥上的铆钉", 1, "乍浦路桥北侧栏杆第一块铭牌上写的建造年份是？（四位数字）", "1927", None, None, "铭牌在桥北头的栏杆上。", "比外白渡桥晚一些。", "1927。", "铆钉还在，电报的密码也在。"),
 ("邮政大楼的钟", 3, "上海邮政大楼顶上的钟楼两侧，各有几尊雕像组？", None, ("一组","两组","三组","没有雕像"), "B", "站在北苏州路的河边抬头。", "左右对称。", "两组，象征邮政通达。", "正确。下一封电报在河对岸。"),
 ("四川路桥的灯", 1, "四川路桥桥头路灯柱有几盏灯头？（填数字）", "3", None, None, "看桥南头的那一根。", "不是一盏。", "3 盏。", "灯亮了，信使的影子出现在桥下。"),
 ("外白渡桥的桁架", 3, "外白渡桥是哪种结构的桥？", None, ("悬索桥","钢桁架桥","石拱桥","斜拉桥"), "B", "看桥身两侧的钢梁。", "是用铆钉连起来的钢架。", "钢桁架桥，1907 年建成。", "回电的最后一段，就藏在桁架里。"),
 ("纪念碑下的回电", 1, "把前面三章收集到的三个数字加起来，回电密码是多少？（1873+1927+3）", "3803", None, None, "回想每一章的第一题。", "1873、1927、3。", "3803。", "回电已发出，这封迟到百年的电报终于有了回音。"),
]
tids = []
for i, g in enumerate(G):
    title, vm, qn, ans, ch, corr, h1, h2, rev, fb = g
    body = {"title": title, "description": qn, "validationMethod": vm, "questionName": qn, "hint1": h1, "hint2": h2,
            "answerReveal": rev, "feedbackText": fb, "duration": 8, "difficulty": "普通", "players": "2-4人", "usageLocation": "户外"}
    if vm == 1: body["questionAnswer"] = ans
    if vm == 3: body.update({"questionA": ch[0], "questionB": ch[1], "questionC": ch[2], "questionD": ch[3], "correctAnswer": corr})
    if vm == 2: body["photoRequireDesc"] = qn
    r = cl.http("POST", "/api/template/publish", token=tok, json_body=body)
    print('template', title, J(r)[:200])
    d = r.get('data'); tids.append(d if isinstance(d, int) else (d or {}).get('id') if isinstance(d, dict) else None)
print('templates', tids)
json.dump(tids, open('/root/sc/co_templates.json', 'w'))
