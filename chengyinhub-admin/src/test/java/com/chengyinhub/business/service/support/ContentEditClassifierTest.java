package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.dto.CmsActivityPublishRequest;
import com.chengyinhub.business.service.support.ContentEditClassifier.EditClass;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** O5b-1:轻改(错别字/措辞)与实质改动(内容/媒体/时间/地点)的确定性分级。 */
class ContentEditClassifierTest {

    private static CmsActivity before() {
        CmsActivity a = new CmsActivity();
        a.setName("周末城市夜跑");
        a.setDescription("周六晚上八点在滨江公园集合，沿江跑五公里，结束后一起吃宵夜。");
        a.setImgUrl("media-asset:11");
        a.setImgArr("media-asset:12;media-asset:13");
        a.setStartDate(new Date(1_800_000_000_000L));
        a.setEndDate(new Date(1_800_003_600_000L));
        a.setAddressName("滨江公园");
        a.setAddress("滨江大道 1 号");
        a.setLongitude("120.1");
        a.setLatitude("30.2");
        return a;
    }

    @Test
    void nothingRequestedIsNone() {
        assertEquals(EditClass.NONE, ContentEditClassifier.classifyActivity(before(), new CmsActivityPublishRequest()));
    }

    @Test
    void typoAndPunctuationFixesAreLight() {
        CmsActivityPublishRequest r = new CmsActivityPublishRequest();
        r.setName("周末城市夜跑！");
        r.setDescription("周六晚上八点在滨江公园集合，沿江跑五公里，结束后一起吃夜宵。");
        assertEquals(EditClass.LIGHT, ContentEditClassifier.classifyActivity(before(), r));
    }

    @Test
    void rewrittenCopyIsSubstantive() {
        CmsActivityPublishRequest r = new CmsActivityPublishRequest();
        r.setDescription("改成线上活动，扫码进群领取福利，详情私聊群主。");
        assertEquals(EditClass.SUBSTANTIVE, ContentEditClassifier.classifyActivity(before(), r));
    }

    @Test
    void anyMediaTimeOrLocationChangeIsSubstantiveEvenWithTinyTextEdits() {
        CmsActivityPublishRequest media = new CmsActivityPublishRequest();
        media.setName("周末城市夜跑!");
        media.setImgUrl("media-asset:99");
        assertEquals(EditClass.SUBSTANTIVE, ContentEditClassifier.classifyActivity(before(), media));

        CmsActivityPublishRequest time = new CmsActivityPublishRequest();
        time.setStartDate(new Date(1_800_000_000_000L + 3_600_000L));
        assertEquals(EditClass.SUBSTANTIVE, ContentEditClassifier.classifyActivity(before(), time));

        CmsActivityPublishRequest place = new CmsActivityPublishRequest();
        place.setLatitude("31.2");
        assertEquals(EditClass.SUBSTANTIVE, ContentEditClassifier.classifyActivity(before(), place));

        CmsActivityPublishRequest address = new CmsActivityPublishRequest();
        address.setAddressName("西湖公园");
        assertEquals(EditClass.SUBSTANTIVE, ContentEditClassifier.classifyActivity(before(), address));
    }

    @Test
    void sameSecondAndSameCoordinateAreNotChanges() {
        CmsActivityPublishRequest r = new CmsActivityPublishRequest();
        r.setStartDate(new Date(1_800_000_000_400L));
        r.setLongitude("120.10");
        assertEquals(EditClass.NONE, ContentEditClassifier.classifyActivity(before(), r));
    }

    @Test
    void shortTextAllowsOnlyOneCharacterAndNeverMoreThanFour() {
        assertEquals(EditClass.LIGHT, ContentEditClassifier.classifyText("夜跑团", "夜跑队"));
        assertEquals(EditClass.SUBSTANTIVE, ContentEditClassifier.classifyText("夜跑团", "骑行队"));
        String longText = "这是一段很长很长的活动介绍文字，用来确认长文本的轻改上限是四个字而不是按百分比无限放大。"
                + "这是一段很长很长的活动介绍文字，用来确认长文本的轻改上限是四个字而不是按百分比无限放大。";
        assertEquals(EditClass.LIGHT, ContentEditClassifier.classifyText(longText, longText.replace("确认", "核实")));
        assertEquals(EditClass.SUBSTANTIVE,
                ContentEditClassifier.classifyText(longText, longText.replace("活动介绍文字", "加微信领红包")));
    }

    @Test
    void clearingOrFillingAnEmptyFieldIsSubstantive() {
        assertEquals(EditClass.SUBSTANTIVE, ContentEditClassifier.classifyText("有内容", ""));
        assertEquals(EditClass.SUBSTANTIVE, ContentEditClassifier.classifyText(null, "新内容"));
    }

    /** S531-1:数字间的小数点/冒号不能被当标点抹掉;金额、日期、时间的任何数字变化都是实质改动。 */
    @Test
    void anyNumberChangeIsSubstantive() {
        assertEquals(EditClass.SUBSTANTIVE, ContentEditClassifier.classifyText("费用10.00元，含饮料", "费用1000元，含饮料"));
        assertEquals(EditClass.SUBSTANTIVE, ContentEditClassifier.classifyText("晚上8:30集合", "晚上830集合"));
        assertEquals(EditClass.SUBSTANTIVE, ContentEditClassifier.classifyText("报名费50元，名额20人", "报名费80元，名额20人"));
        assertEquals(EditClass.SUBSTANTIVE, ContentEditClassifier.classifyText("10月1日开始报名", "10月7日开始报名"));
        assertEquals(EditClass.SUBSTANTIVE, ContentEditClassifier.classifyText("沿江跑五公里", "沿江跑八公里"));
        assertEquals(EditClass.SUBSTANTIVE, ContentEditClassifier.classifyText("费用１０元", "费用１００元"));
        assertEquals(EditClass.SUBSTANTIVE,
                ContentEditClassifier.classifyActivity(before(), withDescription("周六晚上九点在滨江公园集合，沿江跑五公里，结束后一起吃宵夜。")));
        // 数字不变、只改数字外的标点仍是轻改
        assertEquals(EditClass.LIGHT, ContentEditClassifier.classifyText("费用10.00元,含饮料", "费用10.00元，含饮料！"));
    }

    /** S531-1:增删否定词(可退款 → 不可退款)哪怕只差一个字也是实质改动。 */
    @Test
    void addingOrRemovingNegationIsSubstantive() {
        assertEquals(EditClass.SUBSTANTIVE, ContentEditClassifier.classifyText("报名后可退款", "报名后不可退款"));
        assertEquals(EditClass.SUBSTANTIVE, ContentEditClassifier.classifyText("报名后不可退款", "报名后可退款"));
        assertEquals(EditClass.SUBSTANTIVE, ContentEditClassifier.classifyText("现场允许携带宠物", "现场禁止携带宠物"));
        assertEquals(EditClass.SUBSTANTIVE, ContentEditClassifier.classifyText("活动有饮料供应", "活动无饮料供应"));
        assertEquals(EditClass.SUBSTANTIVE, ContentEditClassifier.classifyText("Pets are allowed here", "Pets are not allowed here"));
        assertEquals(EditClass.SUBSTANTIVE,
                ContentEditClassifier.classifyActivity(before(), withDescription("周六晚上八点在滨江公园集合，沿江跑五公里，结束后不吃宵夜。")));
    }

    private static CmsActivityPublishRequest withDescription(String description) {
        CmsActivityPublishRequest r = new CmsActivityPublishRequest();
        r.setDescription(description);
        return r;
    }
}
