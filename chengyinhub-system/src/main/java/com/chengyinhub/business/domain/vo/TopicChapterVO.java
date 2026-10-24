package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.dto.NodeDTO;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public class TopicChapterVO extends CmsTopicChapter {

    @Schema(description = "章节下的节点", example = "")
    private List<TopicNodeVO> nodes;

    /** 沿城市道路的路线坐标(JSON: [{"latitude":..,"longitude":..}, ...])，
     *  由腾讯路线规划算好后返回；为空时前端用节点直连兜底。 */
    @Schema(description = "沿路路线坐标JSON")
    private String routeGeometry;

    public List<TopicNodeVO> getNodes() {
        return nodes;
    }

    /** 章节地点数/玩法数按随章下发的节点现算；库里的 location_count/template_count 只在整题保存时写，单节点增删后会过期。 */
    public void setNodes(List<TopicNodeVO> nodes) {
        this.nodes = nodes;
        if (nodes == null) return;
        long templates = 0L;
        for (TopicNodeVO node : nodes) {
            if (node != null && node.getTemplateId() != null && node.getTemplateId() > 0) templates++;
        }
        setLocationCount((long) nodes.size());
        setTemplateCount(templates);
    }

    public String getRouteGeometry() {
        return routeGeometry;
    }

    public void setRouteGeometry(String routeGeometry) {
        this.routeGeometry = routeGeometry;
    }

}
