'use strict'

// 城市漫游目录(11 城 33 处):公开资料交叉核对,坐标一律为空。只随 subpackageRoam 分包下发,不进主包。
module.exports = {
  "version": 1,
  "reviewNote": "公开资料交叉核对，未实地核验。营业、展览及开放安排以店家或管理方最新公告为准。",
  "cities": [
    {
      "id": "SH",
      "name": "上海"
    },
    {
      "id": "BJ",
      "name": "北京"
    },
    {
      "id": "HZ",
      "name": "杭州"
    },
    {
      "id": "CD",
      "name": "成都"
    },
    {
      "id": "XA",
      "name": "西安"
    },
    {
      "id": "CQ",
      "name": "重庆"
    },
    {
      "id": "SZ",
      "name": "深圳"
    },
    {
      "id": "GZ",
      "name": "广州"
    },
    {
      "id": "WH",
      "name": "武汉"
    },
    {
      "id": "HK",
      "name": "香港"
    },
    {
      "id": "XM",
      "name": "厦门"
    }
  ],
  "places": [
    {
      "id": "sh-shanyin-road",
      "city": "SH",
      "name": "山阴路",
      "category": "street",
      "address": "上海市虹口区山阴路",
      "reason": "看里弄与老公寓的门窗细节，把阅读上海的起点放在居民日常里。",
      "tags": [
        "里弄建筑",
        "街巷漫步"
      ],
      "visitNote": "沿山阴路公共道路步行，住宅内部不作为打卡区域。",
      "reviewedOn": "2026-10-23",
      "reviewMethod": "public_sources",
      "sources": [
        "https://touch.shio.gov.cn/jsp/whjljd_detail.jsp?id=89",
        "https://www.shhk.gov.cn/qyfwy/030002/030002002/030002002002/20181206/7cc9807d-1b83-42ec-8803-c81449ed54c5.html"
      ],
      "coordinate": null
    },
    {
      "id": "sh-luxun-park",
      "city": "SH",
      "name": "鲁迅公园",
      "category": "park",
      "address": "上海市虹口区四川北路2288号",
      "reason": "在树荫与湖边走一段，听听居民合唱，再接着逛周边的文学街巷。",
      "tags": [
        "树荫散步",
        "文学记忆"
      ],
      "visitNote": "公园与园内纪念馆的开放安排不同，参观纪念馆需另查公告。",
      "reviewedOn": "2026-10-23",
      "reviewMethod": "public_sources",
      "sources": [
        "https://www.shanghai.gov.cn/nw17239/20260821/437444e8364445a1a88082f9fbca30b3.html",
        "https://ditu.amap.com/place/B00155KZKW"
      ],
      "coordinate": null
    },
    {
      "id": "sh-uchiyama-books",
      "city": "SH",
      "name": "1927·鲁迅与内山纪念书局",
      "category": "shop",
      "address": "上海市虹口区四川北路2056号",
      "reason": "在老书店延续出的阅读空间里，翻翻海派文化与文学主题的书。",
      "tags": [
        "主题书店",
        "文学空间"
      ],
      "visitNote": "这是推荐收录的阅读空间，未标记为合作商家。",
      "reviewedOn": "2026-10-23",
      "reviewMethod": "public_sources",
      "sources": [
        "https://www.chinawriter.com.cn/n1/2026/0826/c403994-40786320.html",
        "https://whlyj.sh.gov.cn/gqfc/20240828/c93b4408b6ee4268a338c0c794a29a0a.html"
      ],
      "coordinate": null
    },
    {
      "id": "bj-zhuanta-hutong",
      "city": "BJ",
      "name": "砖塔胡同",
      "category": "street",
      "address": "北京市西城区砖塔胡同",
      "reason": "从胡同口的古塔读起，沿公共街巷观察老北京院落与街道的关系。",
      "tags": [
        "胡同漫步",
        "古塔街角"
      ],
      "visitNote": "请沿公共道路行走，不进入未开放的居民院落。",
      "reviewedOn": "2026-10-23",
      "reviewMethod": "public_sources",
      "sources": [
        "https://www.visitbeijing.com.cn/article/4LblHd9FikI",
        "https://www.beijing.gov.cn/renwen/rwzyd/qxdw/jxndqcqx/zhyshj/202309/t20230918_3261515.html"
      ],
      "coordinate": null
    },
    {
      "id": "bj-wanshou-park",
      "city": "BJ",
      "name": "万寿公园",
      "category": "park",
      "address": "北京市西城区白纸坊东街中部路北",
      "reason": "一座围绕日常休闲展开的小公园，适合把行程放慢，看看树木与邻里生活。",
      "tags": [
        "社区公园",
        "慢走休息"
      ],
      "visitNote": "开放时间按季节调整，请查看公园当日公告。",
      "reviewedOn": "2026-10-23",
      "reviewMethod": "public_sources",
      "sources": [
        "https://yllhj.beijing.gov.cn/ggfw/bjsggml/zhgy/xcq/202206/t20220614_2740270.shtml",
        "https://you.ctrip.com/sight/beijing1/112123.html"
      ],
      "coordinate": null
    },
    {
      "id": "bj-zhengyang-books",
      "city": "BJ",
      "name": "正阳书局（西四店）",
      "category": "shop",
      "address": "北京市西城区西四南大街43号",
      "reason": "在万松老人塔院内翻一册北京地方文献，从纸页里接着认识眼前的胡同。",
      "tags": [
        "地方文献",
        "院落书店"
      ],
      "visitNote": "推荐店铺；阅读空间开放与活动安排以书局公告为准。",
      "reviewedOn": "2026-10-23",
      "reviewMethod": "public_sources",
      "sources": [
        "https://www.beijing.gov.cn/renwen/rwzyd/qxdw/jxndqcqx/zhyshj/202309/t20230918_3261515.html",
        "https://www.nationalreading.gov.cn/ydkj/202512/t20251222_943225.html"
      ],
      "coordinate": null
    },
    {
      "id": "hz-mantoushan",
      "city": "HZ",
      "name": "馒头山社区街巷",
      "category": "street",
      "address": "杭州市上城区凤凰山脚路、馒头山社区周边公共街巷",
      "reason": "沿山脚走进白墙黑瓦的生活街巷，看杭州山水之外的社区日常。",
      "tags": [
        "山脚社区",
        "生活街巷"
      ],
      "visitNote": "居民区仅走公共街巷；不拍摄住户室内，不将私人院落列为打卡点。",
      "reviewedOn": "2026-10-23",
      "reviewMethod": "public_sources",
      "sources": [
        "https://www.zjucce.zju.edu.cn/zh-cn/jiedaoshequkaocha/124519.html",
        "https://hznews.hangzhou.com.cn/chengshi/content/2023-04/21/content_8517676.htm"
      ],
      "coordinate": null
    },
    {
      "id": "hz-chengbei-park",
      "city": "HZ",
      "name": "城北体育公园",
      "category": "park",
      "address": "杭州市拱墅区白石路125号",
      "reason": "把打卡换成一段湖边慢走，在运动场与绿地之间感受城北的日常节奏。",
      "tags": [
        "日常运动",
        "湖边慢走"
      ],
      "visitNote": "公园公共区域与馆内体育项目分别管理，项目收费及预约另查。",
      "reviewedOn": "2026-10-23",
      "reviewMethod": "public_sources",
      "sources": [
        "https://z.hangzhou.com.cn/2023/zjyl/content/content_8651963.html",
        "https://ditu.amap.com/place/B023B09Y7Q"
      ],
      "coordinate": null
    },
    {
      "id": "hz-silk-museum",
      "city": "HZ",
      "name": "中国丝绸博物馆",
      "category": "landmark",
      "address": "杭州市西湖区玉皇山路73-1号",
      "reason": "从织物纹样与服饰看杭州的另一面，留一点时间观察材料与工艺。",
      "tags": [
        "纺织工艺",
        "服饰文化"
      ],
      "visitNote": "展览、预约与开放安排以场馆最新公告为准。",
      "reviewedOn": "2026-10-23",
      "reviewMethod": "public_sources",
      "sources": [
        "https://www.chinasilkmuseum.com/gwgk/info_4.aspx?itemid=8790",
        "https://www.amap.com/place/B023B09JLL"
      ],
      "coordinate": null
    },
    {
      "id": "cd-tangba-street",
      "city": "CD",
      "name": "镋钯街",
      "category": "street",
      "address": "成都市锦江区镋钯街公共道路",
      "reason": "沿川西街巷的尺度慢走，看看旧街建筑与今日小店如何并排生活。",
      "tags": [
        "旧街肌理",
        "街角观察"
      ],
      "visitNote": "周末与节假日可能热闹；推荐的是步行观察，不承诺人少。",
      "reviewedOn": "2026-10-23",
      "reviewMethod": "public_sources",
      "sources": [
        "https://www.nbd.com.cn/articles/2019-04-29/1326718.html",
        "https://ditu.amap.com/place/B0FFH6JHJ6"
      ],
      "coordinate": null
    },
    {
      "id": "cd-wangjiang-park",
      "city": "CD",
      "name": "望江楼公园",
      "category": "park",
      "address": "成都市武侯区望江路30号至50号",
      "reason": "沿竹林与江边走一段，认识与薛涛有关的城市记忆。",
      "tags": [
        "竹林漫步",
        "江边休息"
      ],
      "visitNote": "公共园区与文物保护区的票务、开放规则不同，请分开核对。",
      "reviewedOn": "2026-10-23",
      "reviewMethod": "public_sources",
      "sources": [
        "https://www.wangjianglou.com/about.html",
        "https://ditu.amap.com/place/B001C06R0O"
      ],
      "coordinate": null
    },
    {
      "id": "cd-a4-museum",
      "city": "CD",
      "name": "A4美术馆",
      "category": "landmark",
      "address": "成都市天府新区麓山大道二段18号麓镇山顶广场",
      "reason": "留一个下午给当代艺术，在展厅与社区之间切换观看城市的方式。",
      "tags": [
        "当代艺术",
        "社区文化"
      ],
      "visitNote": "已按迁址后的麓镇馆址收录，请勿与麓湖的A4X艺术中心混淆。",
      "reviewedOn": "2026-10-23",
      "reviewMethod": "public_sources",
      "sources": [
        "https://www.a4artmuseum.com/about-us/contact/",
        "https://you.ctrip.com/sight/chengdu104/140776071.html"
      ],
      "coordinate": null
    },
    {
      "id": "xa-banpo-art",
      "city": "XA",
      "name": "半坡国际艺术区",
      "category": "street",
      "address": "西安市灞桥区纺织西街238号",
      "reason": "沿旧纺织厂的公共街巷看厂房、创作空间与工业留下的尺度。",
      "tags": [
        "旧厂街巷",
        "艺术空间"
      ],
      "visitNote": "工作室并非全部向公众开放，入内前请征得经营者同意。",
      "reviewedOn": "2026-10-23",
      "reviewMethod": "public_sources",
      "sources": [
        "https://big5.cri.cn/gate/big5/sn.cri.cn/n/20180424/5fbab288-509d-90b8-ac21-7b63bd259273.html",
        "https://you.ctrip.com/sight/xian7/1475462.html"
      ],
      "coordinate": null
    },
    {
      "id": "xa-xicheng-park",
      "city": "XA",
      "name": "环城西苑",
      "category": "park",
      "address": "西安市莲湖区环城西路、西门外护城河沿线",
      "reason": "在城墙外的公共绿地走走，看护城河、树荫与居民的晨晚活动。",
      "tags": [
        "护城河绿地",
        "日常散步"
      ],
      "visitNote": "收录的是城墙外公共绿地，登城参观需另查票务。",
      "reviewedOn": "2026-10-23",
      "reviewMethod": "public_sources",
      "sources": [
        "https://xadfz.xa.gov.cn/xadq/dqtk/63edda39f8fd1c4c21407585.html",
        "https://you.ctrip.com/sight/xian7/121516.html"
      ],
      "coordinate": null
    },
    {
      "id": "xa-shaanxi-art",
      "city": "XA",
      "name": "陕西省美术博物馆",
      "category": "landmark",
      "address": "西安市碑林区长安北路14号",
      "reason": "用一场美术展切换旅行节奏，从绘画与地方工艺接近陕西文化。",
      "tags": [
        "美术展览",
        "地方工艺"
      ],
      "visitNote": "开放、展期与预约以馆方公告为准。",
      "reviewedOn": "2026-10-23",
      "reviewMethod": "public_sources",
      "sources": [
        "https://www.sxpam.com.cn/html/content/index.html",
        "https://www.amap.com/place/B001D04JH6"
      ],
      "coordinate": null
    },
    {
      "id": "cq-sujia-trail",
      "city": "CQ",
      "name": "苏家坝步道",
      "category": "street",
      "address": "重庆市南岸区铜元局站至双峰山、铜元之光城市阳台一带",
      "reason": "沿连接轨道站与山坡社区的步道，观察重庆真实的高差与日常通行。",
      "tags": [
        "山城步道",
        "立体街巷"
      ],
      "visitNote": "台阶与高差较多，按体力选择路段，雨天注意路面。",
      "reviewedOn": "2026-10-23",
      "reviewMethod": "public_sources",
      "sources": [
        "https://cq.gov.cn/zwgk/zfxxgkml/zdlyxxgk/ggwh/ly/zxdt/202605/t20260520_15691032.html",
        "https://cq.people.com.cn/n2/2022/0701/c365401-40019120-3.html"
      ],
      "coordinate": null
    },
    {
      "id": "cq-pingdingshan-park",
      "city": "CQ",
      "name": "平顶山文化公园",
      "category": "park",
      "address": "重庆市沙坪坝区石小路155号",
      "reason": "沿山间步道慢走，在绿荫与观景处看看山城社区的另一种高度。",
      "tags": [
        "山间绿荫",
        "日常登高"
      ],
      "visitNote": "山路与台阶较多，请选择合适的步行路段。",
      "reviewedOn": "2026-10-23",
      "reviewMethod": "public_sources",
      "sources": [
        "https://www.cq.gov.cn/ywdt/jrcq/202202/t20220203_10369346.html",
        "https://www.amap.com/place/B00170I04F"
      ],
      "coordinate": null
    },
    {
      "id": "cq-jiaotong-tea",
      "city": "CQ",
      "name": "陈安健交通茶馆",
      "category": "shop",
      "address": "重庆市九龙坡区黄桷坪正街7号",
      "reason": "在木桌、茶盏与聊天声之间坐一会儿，把看城市的速度交还给日常。",
      "tags": [
        "老茶馆",
        "在地生活"
      ],
      "visitNote": "按当前地图门店名收录，勿与同名茶馆混淆；拍摄他人请先征得同意。",
      "reviewedOn": "2026-10-23",
      "reviewMethod": "public_sources",
      "sources": [
        "https://www.cqjlp.gov.cn/zwxx/qxdt/202606/t20260618_15763966.html",
        "https://ditu.amap.com/place/B001790I1V"
      ],
      "coordinate": null
    },
    {
      "id": "sz-oct-loft",
      "city": "SZ",
      "name": "华侨城创意文化园",
      "category": "street",
      "address": "深圳市南山区华侨城创意文化园公共街巷",
      "reason": "沿旧厂房之间的街巷，看设计工作室、展览空间与树木相邻的日常。",
      "tags": [
        "厂房更新",
        "设计街巷"
      ],
      "visitNote": "园内工作室和场馆分别管理，开放与活动安排需逐店核对。",
      "reviewedOn": "2026-10-23",
      "reviewMethod": "public_sources",
      "sources": [
        "https://www.szns.gov.cn/mlns/nsgk_113865/lechuang/content/post_3780578.html",
        "https://wtl.sz.gov.cn/ggfw/whl/whcyyqlyb/index.html"
      ],
      "coordinate": null
    },
    {
      "id": "sz-xiangmi-park",
      "city": "SZ",
      "name": "香蜜公园",
      "category": "park",
      "address": "深圳市福田区农园路30号",
      "reason": "在荔枝林与步道间留一段散步时间，看看深圳社区里的绿意。",
      "tags": [
        "城市绿地",
        "林间步道"
      ],
      "visitNote": "此处为农园路香蜜公园，请勿与建设中的香蜜湖公园混淆。",
      "reviewedOn": "2026-10-23",
      "reviewMethod": "public_sources",
      "sources": [
        "https://cgj.sz.gov.cn/xsmh/gysz/twf/etly/content/post_10841297.html",
        "https://www.amap.com/place/B0FFG1V706"
      ],
      "coordinate": null
    },
    {
      "id": "sz-hexiangning-art",
      "city": "SZ",
      "name": "何香凝美术馆",
      "category": "landmark",
      "address": "深圳市南山区深南大道9013号",
      "reason": "给作品与文献留一点观看时间，从艺术家的经历读到城市之外的世界。",
      "tags": [
        "美术文献",
        "展厅慢看"
      ],
      "visitNote": "展览、开放和预约以美术馆公告为准。",
      "reviewedOn": "2026-10-23",
      "reviewMethod": "public_sources",
      "sources": [
        "https://www.sz.gov.cn/szzt2010/szwtt/wtcg/whcg/content/mpost_11131257.html",
        "https://www.amap.com/place/B02F30248A"
      ],
      "coordinate": null
    },
    {
      "id": "gz-xinhepu",
      "city": "GZ",
      "name": "新河浦公共街巷",
      "category": "street",
      "address": "广州市越秀区新河浦路、恤孤院路周边公共道路",
      "reason": "沿红砖洋房与河涌观察建筑细节，读广州近代社区留下的街巷尺度。",
      "tags": [
        "红砖洋房",
        "沿河街巷"
      ],
      "visitNote": "街区仍有居民生活，私宅不进入，沿公共道路步行。",
      "reviewedOn": "2026-10-23",
      "reviewMethod": "public_sources",
      "sources": [
        "https://www.yuexiu.gov.cn/zjyx/yxjd/bgjq/content/post_8665420.html",
        "https://sthjj.gz.gov.cn/mlzg/sfyb/mlxb/content/post_10696347.html"
      ],
      "coordinate": null
    },
    {
      "id": "gz-xiaogang-park",
      "city": "GZ",
      "name": "晓港公园",
      "category": "park",
      "address": "广州市海珠区前进路146号",
      "reason": "沿竹林、湖面与老树走一段，看看老广日常休息的城市绿地。",
      "tags": [
        "竹林公园",
        "邻里休息"
      ],
      "visitNote": "收录公园公共园林，不包含已停业的旧游乐场项目。",
      "reviewedOn": "2026-10-23",
      "reviewMethod": "public_sources",
      "sources": [
        "https://www.haizhu.gov.cn/hzdt/ztlm/tzhz/rjhj/lyjd/smgy/content/post_9055133.html",
        "https://www.amap.com/place/B00140UFR2"
      ],
      "coordinate": null
    },
    {
      "id": "gz-kuiyuan-art",
      "city": "GZ",
      "name": "逵园艺术馆",
      "category": "landmark",
      "address": "广州市越秀区恤孤院路9号",
      "reason": "在老洋房里看当代展览，把建筑细节与作品放在一起慢慢看。",
      "tags": [
        "洋房艺术",
        "小型展览"
      ],
      "visitNote": "展览与楼内经营空间的开放安排分别以现场公告为准。",
      "reviewedOn": "2026-10-23",
      "reviewMethod": "public_sources",
      "sources": [
        "https://www.gzass.gd.cn/whzc/wenhuasheshi/wenhuachangguan/326.html",
        "https://www.amap.com/place/B00141KR0X"
      ],
      "coordinate": null
    },
    {
      "id": "wh-qingdao-road",
      "city": "WH",
      "name": "青岛路公共街巷",
      "category": "street",
      "address": "武汉市江岸区青岛路、平和打包厂周边公共道路",
      "reason": "沿红砖工业建筑观察老汉口的城市更新，从街道读到曾经的商贸生活。",
      "tags": [
        "工业建筑",
        "老城街巷"
      ],
      "visitNote": "沿公共道路步行，楼内商业空间及拍摄规定以经营方公告为准。",
      "reviewedOn": "2026-10-23",
      "reviewMethod": "public_sources",
      "sources": [
        "https://wlj.wuhan.gov.cn/whly/wbsy/202201/t20220112_1894179.shtml",
        "https://news.cjn.cn/whpd/lbt_19946/202508/t5194738.htm"
      ],
      "coordinate": null
    },
    {
      "id": "wh-jiefang-park",
      "city": "WH",
      "name": "解放公园",
      "category": "park",
      "address": "武汉市江岸区解放大道1861号",
      "reason": "在湖边与树荫间散步，把看展探店之间的空档留给一段城市绿地。",
      "tags": [
        "树荫慢走",
        "城市公园"
      ],
      "visitNote": "花期、活动日可能热闹；不将园内经营项目视为全部免费。",
      "reviewedOn": "2026-10-23",
      "reviewMethod": "public_sources",
      "sources": [
        "https://ylj.wuhan.gov.cn/zwgk/zwxxgkzl_12298/jggk_12304/xsdwszjzz_12308/202001/t20200110_726396.shtml",
        "https://ditu.amap.com/place/B001B0IZT0"
      ],
      "coordinate": null
    },
    {
      "id": "wh-united-art",
      "city": "WH",
      "name": "合美术馆",
      "category": "landmark",
      "address": "武汉市洪山区野芷湖西路16号",
      "reason": "从当代艺术与青年创作中看另一面的武汉，给一件作品多停留几分钟。",
      "tags": [
        "当代艺术",
        "青年创作"
      ],
      "visitNote": "展期、预约及开放状态以场馆最新公告为准。",
      "reviewedOn": "2026-10-23",
      "reviewMethod": "public_sources",
      "sources": [
        "https://wlt.hubei.gov.cn/zfxxgk/fdzdgknr/gysyjs/ggwhfw/ggmsg/202011/t20201102_2994450.shtml",
        "https://you.ctrip.com/sight/wuhan145/69376375.html"
      ],
      "coordinate": null
    },
    {
      "id": "hk-tai-nan-street",
      "city": "HK",
      "name": "大南街",
      "category": "street",
      "address": "香港九龙深水埗大南街公共道路",
      "reason": "从皮革、布料与今日创作小店之间，观察深水埗制造业与社区生活的连接。",
      "tags": [
        "手作街巷",
        "社区观察"
      ],
      "visitNote": "街道与店铺正常经营，拍摄和入店参观请尊重店家安排。",
      "reviewedOn": "2026-10-23",
      "reviewMethod": "public_sources",
      "sources": [
        "https://www.discoverhongkong.com/tc/place-to-go/travel.guide-tai-nan-street.html",
        "https://www.td.gov.hk/tc/traffic_notices/index_id_79034.html"
      ],
      "coordinate": null
    },
    {
      "id": "hk-cattle-depot-park",
      "city": "HK",
      "name": "牛棚艺术公园",
      "category": "park",
      "address": "香港九龙土瓜湾新山道与东九龙走廊交界、牛棚后方",
      "reason": "在保留工业痕迹的公共休憩空间走走，看看红砖、绿地与艺术社区如何相邻。",
      "tags": [
        "工业遗存",
        "公共艺术"
      ],
      "visitNote": "公园与前方牛棚艺术村是不同空间，工作室开放另查。",
      "reviewedOn": "2026-10-23",
      "reviewMethod": "public_sources",
      "sources": [
        "https://www.lcsd.gov.hk/sc/parks/cdap/index.html",
        "https://www.archsd.gov.hk/sc/exhibition/8254_Revitalisation_of_the_Rear_Portion_of_the_Cattle_Depot.html"
      ],
      "coordinate": null
    },
    {
      "id": "hk-aco-books",
      "city": "HK",
      "name": "艺鵠书店",
      "category": "shop",
      "address": "香港湾仔轩尼诗道365–367号富德楼14楼",
      "reason": "翻翻艺术书与独立出版，从一本小书接近本地创作的日常。",
      "tags": [
        "独立出版",
        "楼上书店"
      ],
      "visitNote": "书店位于14楼，请按楼宇及书店的开放安排前往。",
      "reviewedOn": "2026-10-23",
      "reviewMethod": "public_sources",
      "sources": [
        "https://www.aco.hk/aco-chi",
        "https://www.art-mate.net/tc/doc/44408?name=%E8%89%BA%E9%B5%A0%E6%9B%B8%E5%BA%97"
      ],
      "coordinate": null
    },
    {
      "id": "xm-huaxin-road",
      "city": "XM",
      "name": "华新路公共街巷",
      "category": "street",
      "address": "厦门市思明区华新路、幸福路周边公共道路",
      "reason": "沿华侨老别墅与树荫慢走，看一段仍与居民日常交织的厦门街巷。",
      "tags": [
        "老别墅",
        "树荫街巷"
      ],
      "visitNote": "不将旧攻略中停业的书店作为目的地；私人住宅不进入。",
      "reviewedOn": "2026-10-23",
      "reviewMethod": "public_sources",
      "sources": [
        "https://www.hxfzzx.com/2026-05/06/content_2338096.htm",
        "https://news.xmnn.cn/xmxw/202607/t20260710_439108.html"
      ],
      "coordinate": null
    },
    {
      "id": "xm-zhonglun-park",
      "city": "XM",
      "name": "忠仑公园",
      "category": "park",
      "address": "厦门市湖里区金尚路、吕岭路沿线忠仑公园",
      "reason": "沿林间步道与缓坡看看城市绿意，把远行的节奏放回一次日常散步。",
      "tags": [
        "林间散步",
        "轻量登高"
      ],
      "visitNote": "公园入口不止一处，请按计划路段选择；花期与活动安排另查。",
      "reviewedOn": "2026-10-23",
      "reviewMethod": "public_sources",
      "sources": [
        "https://you.ctrip.com/sight/xiamen21/119140.html",
        "https://www.amap.com/place/B025003JXK"
      ],
      "coordinate": null
    },
    {
      "id": "xm-three-shadows",
      "city": "XM",
      "name": "三影堂厦门摄影艺术中心",
      "category": "landmark",
      "address": "厦门市集美区杏林湾商务营运中心二号楼裙楼301",
      "reason": "用一场摄影展切换观看方式，在影像里看看城市与人的关系。",
      "tags": [
        "摄影艺术",
        "影像观看"
      ],
      "visitNote": "从连接1号楼和2号楼的大平台进入，展期与票务以中心公告为准。",
      "reviewedOn": "2026-10-23",
      "reviewMethod": "public_sources",
      "sources": [
        "https://www.threeshadows.cn/cn/about/visit/",
        "https://you.ctrip.com/sight/xiamen21/2105373.html"
      ],
      "coordinate": null
    }
  ]
}
