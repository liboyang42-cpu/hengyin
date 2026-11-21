package com.chengyinhub.business.domain.dto.seed;

import java.math.BigDecimal;
import java.util.List;

/** District density report request: a named CITY board area, a box, or a drawn polygon of [lat, lng] points. */
public class DensityQuery {
    private String boardId;
    private BigDecimal south;
    private BigDecimal west;
    private BigDecimal north;
    private BigDecimal east;
    private List<List<BigDecimal>> polygon;
    private String cityKeyword;

    public String getBoardId() { return boardId; }
    public void setBoardId(String boardId) { this.boardId = boardId; }
    public BigDecimal getSouth() { return south; }
    public void setSouth(BigDecimal south) { this.south = south; }
    public BigDecimal getWest() { return west; }
    public void setWest(BigDecimal west) { this.west = west; }
    public BigDecimal getNorth() { return north; }
    public void setNorth(BigDecimal north) { this.north = north; }
    public BigDecimal getEast() { return east; }
    public void setEast(BigDecimal east) { this.east = east; }
    public List<List<BigDecimal>> getPolygon() { return polygon; }
    public void setPolygon(List<List<BigDecimal>> polygon) { this.polygon = polygon; }
    public String getCityKeyword() { return cityKeyword; }
    public void setCityKeyword(String cityKeyword) { this.cityKeyword = cityKeyword; }
}
