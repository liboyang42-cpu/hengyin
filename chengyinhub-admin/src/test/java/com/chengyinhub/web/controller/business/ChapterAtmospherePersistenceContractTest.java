package com.chengyinhub.web.controller.business;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.util.ChapterAtmospherePreset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 章节配色必须真的落库。
 *
 * <p>2026-09-06 之前:小程序有五档配色选择器,ChapterDTO 上却没有 atmospherePreset 字段,
 * 两条写路径都走 {@code BeanUtils.copyProperties} —— DTO 少一个字段这一列就永远是默认值。
 * 用户选了颜色、界面也显示对了,存进去的却始终是 DEFAULT,**零报错**。
 * 这条合同盯的就是这种「控件活着、数据没到」的洞。</p>
 */
class ChapterAtmospherePersistenceContractTest
{
    private static String source(String relativeToRepoRoot) throws Exception
    {
        Path repoRoot = Paths.get(System.getProperty("user.dir")).getParent();
        Path file = repoRoot.resolve(relativeToRepoRoot);
        assertTrue(Files.exists(file), "源文件不在:" + file);
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    @Test
    void dtoCarriesTheField() throws Exception
    {
        ChapterDTO dto = new ChapterDTO();
        dto.setAtmospherePreset("BLUE");
        assertEquals("BLUE", dto.getAtmospherePreset(), "DTO 少了这个字段,列就永远写不进去");
    }

    @Test
    void bothWritePathsNormalizeInsteadOfPassingThrough() throws Exception
    {
        String impl = source("chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TopicContentGraphAuthoringServiceImpl.java");
        int hits = impl.split("setAtmospherePreset\\(ChapterAtmospherePreset\\.requireValid\\(", -1).length - 1;
        assertEquals(2, hits,
                "两条写路径(v1 saveChapters / v2 flow)都必须显式收敛枚举 —— "
                        + "靠 copyProperties 隐式透传等于让前端任意字符串直接落库,同一个方法里已经栽过两次");
        assertTrue(impl.contains("chapter.setAtmospherePreset(ChapterAtmospherePreset.requireValid(chapterDTO.getAtmospherePreset()))"),
                "V1章节写口必须保留显式规范化");
        assertTrue(impl.contains("chapter.setAtmospherePreset(ChapterAtmospherePreset.requireValid(source.getAtmospherePreset()))"),
                "V2块编译写口必须保留显式规范化");
        String facade = source("chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsTopicServiceImpl.java");
        assertTrue(facade.contains("contentGraphAuthoringService.saveTopicChapters("),
                "原公共门面必须调用真实内容图写口");
    }

    @Test
    void reopenCopiesItToTheNewEdition() throws Exception
    {
        String impl = source("chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TopicEditionReopenServiceImpl.java");
        assertTrue(impl.contains("copy.setAtmospherePreset(source.getAtmospherePreset())"),
                "复期不搬配色,新一期整章会静默掉回默认黑");

        String mapper = source("chengyinhub-system/src/main/resources/mapper/business/TopicEditionReopenMapper.xml");
        assertTrue(mapper.contains("atmosphere_preset"), "insert 列里没有这一列,setter 设了也白设");
        assertTrue(mapper.contains("#{atmospherePreset}"), "占位符缺了,列数会对不上直接报错");
    }

    @Test
    void legacyValuesStillLoadAndAreRewrittenToTheNewScale()
    {
        assertEquals("BLUE", ChapterAtmospherePreset.requireValid("NIGHT"));
        assertEquals("YELLOW", ChapterAtmospherePreset.requireValid("ARCHIVE"));
        assertEquals("RED", ChapterAtmospherePreset.requireValid("NEON"));
        assertEquals(ChapterAtmospherePreset.DEFAULT, ChapterAtmospherePreset.requireValid("MOSS"));
        assertEquals(ChapterAtmospherePreset.DEFAULT, ChapterAtmospherePreset.requireValid(null));
    }

    /** ★负控:把「显式收敛」那两行拿掉,这条合同必须变红 —— 不然它挡不住回归。 */
    @Test
    void negativeControlRemovingNormalizationTurnsRed() throws Exception
    {
        String impl = source("chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/TopicContentGraphAuthoringServiceImpl.java");
        String tampered = impl.replace("setAtmospherePreset(ChapterAtmospherePreset.requireValid(", "setAtmospherePreset((");
        assertNotNull(tampered);
        assertFalse(tampered.contains("setAtmospherePreset(ChapterAtmospherePreset.requireValid("),
                "负控没改动到目标片段,这条负控是假的");
        int hits = tampered.split("setAtmospherePreset\\(ChapterAtmospherePreset\\.requireValid\\(", -1).length - 1;
        assertEquals(0, hits, "拿掉收敛之后判据仍然命中 —— 说明这条合同的判据写错了地方");
    }
}
