package com.chengyinhub.business.service.impl;

import com.alibaba.druid.wall.WallConfig;
import com.alibaba.druid.wall.spi.MySqlWallProvider;
import com.chengyinhub.business.mapper.ViewCreativeSquareMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 帖子列表补全时,某一类批量入参为空很常见(比如这一页没有自由探索帖)。这些语句空集时渲染成
 * {@code where del_flag = 0 and 1 = 0},应用数据源的 Druid wall 判「部分恒假」直接拒绝,整页报错。
 * 测试库没有帖子、MySQL IT 走裸数据源,都看不到。所以空集不下发 SQL。
 */
class ViewCreativeSquareEmptyBatchTest {

    @Test
    void wallStillRejectsTheEmptyListGuardShape() {
        WallConfig config = new WallConfig(MySqlWallProvider.DEFAULT_CONFIG_DIR);
        config.setMultiStatementAllow(false);
        MySqlWallProvider wall = new MySqlWallProvider(config);

        assertFalse(wall.check("select member_id, count(*) cnt from player_node_progress "
                + "where del_flag = 0 and 1 = 0 group by member_id").getViolations().isEmpty(),
                "负控:wall 不再拒绝这种写法时,本合同失去意义");
    }

    @Test
    void emptyBatchesReturnEmptyWithoutReachingTheMapper() {
        ViewCreativeSquareMapper mapper = mock(ViewCreativeSquareMapper.class);
        ViewCreativeSquareServiceImpl service = new ViewCreativeSquareServiceImpl();
        ReflectionTestUtils.setField(service, "viewCreativeSquareMapper", mapper);

        assertTrue(service.selectNodeCountsByTopicIds(Collections.emptyList()).isEmpty());
        assertTrue(service.selectProgressCountsByMemberActivities(Collections.emptyList()).isEmpty());
        assertTrue(service.selectProgressCountsByMemberTopics(Collections.emptyList()).isEmpty());
        assertTrue(service.selectMedalCountsByMemberTopics(Collections.emptyList()).isEmpty());
        assertTrue(service.selectExplorePassesByMemberActivities(Collections.emptyList()).isEmpty());
        assertTrue(service.selectRequiredChapterCountsByTopicIds(Collections.emptyList()).isEmpty());
        assertTrue(service.selectRedeemedRequiredChapterCountsByRegistrations(Collections.emptyList()).isEmpty());

        verifyNoInteractions(mapper);
    }
}
