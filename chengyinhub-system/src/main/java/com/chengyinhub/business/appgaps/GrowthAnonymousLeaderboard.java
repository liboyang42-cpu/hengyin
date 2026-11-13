package com.chengyinhub.business.appgaps;

import com.chengyinhub.business.domain.dto.LeaderboardQueryDTO;
import com.chengyinhub.business.domain.vo.LeaderboardItemVO;
import com.chengyinhub.business.domain.vo.MemberLeaderboardVO;
import com.chengyinhub.business.service.IGrowthService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * App 匿名成长榜（/api/growth/leaderboard/anonymous）。
 * 复用 /api/growth/leaderboard 的同一份排名，只投影：他人行固定「匿名玩家」且不带头像/会员号，
 * 自己的行只带名次与分数。名次以榜单行为准，保证 me 与榜内自身行一致。
 */
@Service
public class GrowthAnonymousLeaderboard {
    public static final String ANONYMOUS = "匿名玩家";
    private final IGrowthService growth;

    public GrowthAnonymousLeaderboard(IGrowthService growth) { this.growth = growth; }

    public Map<String, Object> read(long memberId, LeaderboardQueryDTO query) {
        MemberLeaderboardVO board = growth.getLeaderboard(memberId, query);
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> selfRow = null;
        List<LeaderboardItemVO> list = board.getList() == null ? List.of() : board.getList();
        for (int i = 0; i < list.size() && i < 100; i++) {
            LeaderboardItemVO item = list.get(i);
            boolean self = Objects.equals(item.getMemberId(), memberId);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", i + 1);
            row.put("score", item.getScore());
            if (self) {
                if (selfRow != null) { self = false; }
                else { selfRow = row; }
            }
            if (!self) row.put("nickname", ANONYMOUS);
            row.put("self", self);
            rows.add(row);
        }
        LeaderboardItemVO meVo = board.getMe();
        Map<String, Object> me = new LinkedHashMap<>();
        Integer rank = meVo == null ? null : meVo.getRank();
        Long score = meVo == null ? null : meVo.getScore();
        if (selfRow != null) {
            rank = (Integer) selfRow.get("rank");
            score = (Long) selfRow.get("score");
        } else if (rank != null && (rank <= 0 || rank <= rows.size())) {
            // 并列分数下单独的我的名次查询落在榜内却不在榜行里：不编造位置，按未上榜呈现。
            rank = null;
        }
        if (rank != null) me.put("rank", rank);
        me.put("score", score);
        if (meVo != null && meVo.getRankPercentage() != null) me.put("rankPercentage", meVo.getRankPercentage());
        me.put("self", true);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("metric", board.getMetric());
        out.put("period", board.getPeriod());
        out.put("list", rows);
        out.put("me", me);
        return out;
    }
}
