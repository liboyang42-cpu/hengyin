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

    public void setNodes(List<TopicNodeVO> nodes) {
        this.nodes = nodes;
    }

    public String getRouteGeometry() {
        return routeGeometry;
    }

    public void setRouteGeometry(String routeGeometry) {
        this.routeGeometry = routeGeometry;
    }

}
