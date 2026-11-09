package com.chengyinhub.business.service.safety;

import java.util.HashMap;
import java.util.Map;

/**
 * 敏感词匹配前的文本归一(Round5D,纯 Java,无三方库):
 * 全角转半角、大小写统一、常用繁体转简体,并丢掉夹在字之间的符号、空白和表情 ——
 * 只保留字母、数字和汉字,「赌.博」「赌 博」「赌😀博」都归一成「赌博」。
 */
public final class SensitiveTextNormalizer {

    /** 常用繁→简对照(成对排列:繁简繁简…)。只收高频字,够用即可;漏网的仍由微信机审兜底。 */
    private static final String T2S_PAIRS =
            "說说話话語语這这個个們们來来時时會会國国開开關关門门問问間间聞闻長长發发髮发車车東东書书學学習习"
          + "買买賣卖錢钱點点黨党槍枪彈弹藥药賭赌騙骗殺杀戰战爭争亂乱惡恶罵骂慾欲體体愛爱為为與与萬万從从"
          + "對对現现後后麼么樣样還还進进過过動动業业經经頭头號号軍军傳传員员電电網网機机幣币銀银貸贷黃黄"
          + "賤贱淫淫婦妇媽妈爺爷們们獨独義义氣气讓让認认識识讀读寫写聽听見见覽览觀观親亲視视覺觉紅红綠绿"
          + "藍蓝黑黑紙纸線线級级組组織织結结給给統统絕绝縣县區区鄉乡鎮镇廠厂場场塊块壞坏夢梦嬰婴孩孩寶宝"
          + "實实寫写將将專专尋寻導导屍尸幾几廣广張张強强歸归當当彥彦徵征後后徹彻戲戏戶户掃扫掛挂換换據据"
          + "擊击擇择攝摄敗败數数斷断無无舊旧顯显權权條条標标樂乐歡欢歲岁歷历殘残滅灭漢汉潛潜濕湿灣湾燒烧"
          + "煙烟獄狱獸兽產产畫画療疗癮瘾盜盗盡尽監监碼码礦矿禮礼禍祸窮穷竊窃節节範范簡简糧粮緊紧總总"
          + "罰罚聯联職职膠胶腦脑臉脸興兴舉举艷艳藝艺蘇苏蟲虫衛卫補补製制複复襲袭規规訊讯討讨記记許许設设"
          + "診诊証证評评詐诈試试詩诗誘诱誌志謀谋謊谎謝谢證证護护變变豐丰貓猫貝贝負负財财貨货販贩貪贪"
          + "責责費费資资賊贼賄贿賠赔賬账質质購购贈赠趕赶跡迹蹤踪軟软轉转輕轻較较輪轮辦办農农邊边遊游運运"
          + "違违遠远適适選选遺遗鄧邓鄭郑醫医釋释針针鈔钞鐵铁鍵键鏡镜鐘钟門门閃闪閉闭陣阵陰阴陸陆陽阳隊队"
          + "隨随險险隱隐雜杂雙双雞鸡離离難难電电靈灵響响頁页順顺須须頓顿預预領领題题顏颜類类顧顾風风飛飞"
          + "飯饭飲饮館馆馬马駕驾騎骑驗验髒脏鬥斗魚鱼鳥鸟鹽盐麗丽齊齐龍龙";

    private static final Map<Integer, Integer> T2S = new HashMap<Integer, Integer>();

    static {
        int[] cps = T2S_PAIRS.codePoints().toArray();
        for (int i = 0; i + 1 < cps.length; i += 2) {
            if (cps[i] != cps[i + 1]) T2S.put(cps[i], cps[i + 1]);
        }
    }

    private SensitiveTextNormalizer() {
    }

    /** 单个码点归一;返回 -1 表示该字符不参与匹配(符号/空白/表情等)。 */
    static int normalizeCodePoint(int cp) {
        if (cp >= 0xFF01 && cp <= 0xFF5E) cp -= 0xFEE0;      // 全角 ASCII → 半角
        else if (cp == 0x3000) return -1;                     // 全角空格
        Integer simplified = T2S.get(cp);
        if (simplified != null) cp = simplified;
        if (!Character.isLetterOrDigit(cp)) return -1;
        return Character.toLowerCase(cp);
    }

    public static String normalize(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            int n = normalizeCodePoint(cp);
            if (n >= 0) sb.appendCodePoint(n);
        });
        return sb.toString();
    }
}
