package com.chengyinhub.business.domain.map;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 一次公开地图读取的确定结果。聚类只有「不支持」，没有个数。 */
public class MapPublicResult {

    private String datum;
    private Cluster cluster;
    private List<Layer> layers = new ArrayList<>();
    private String nextCursor;

    public String getDatum() { return datum; }
    public void setDatum(String datum) { this.datum = datum; }
    public Cluster getCluster() { return cluster; }
    public void setCluster(Cluster cluster) { this.cluster = cluster; }
    public List<Layer> getLayers() { return layers; }
    public void setLayers(List<Layer> layers) { this.layers = layers; }
    public String getNextCursor() { return nextCursor; }
    public void setNextCursor(String nextCursor) { this.nextCursor = nextCursor; }

    public static class Cluster {
        private boolean supported;

        public static Cluster unsupported() {
            Cluster cluster = new Cluster();
            cluster.supported = false;
            return cluster;
        }

        public boolean isSupported() { return supported; }
        public void setSupported(boolean supported) { this.supported = supported; }
    }

    public static class Capabilities {
        private boolean read;
        private boolean count;
        private boolean cluster;

        public boolean isRead() { return read; }
        public void setRead(boolean read) { this.read = read; }
        public boolean isCount() { return count; }
        public void setCount(boolean count) { this.count = count; }
        public boolean isCluster() { return cluster; }
        public void setCluster(boolean cluster) { this.cluster = cluster; }
    }

    public static class Item {
        private String sourceType;
        private Long sourceId;
        private String name;
        private BigDecimal latitude;
        private BigDecimal longitude;
        private String address;
        private String cover;
        private String npcAvatar;
        private Integer poiType;
        private Boolean found;
        private Boolean pendingRedeem;
        private Integer businessStatus;

        public static Item from(MapPublicRow row) {
            Item item = new Item();
            item.sourceType = row.getSourceType();
            item.sourceId = row.getSourceId();
            item.name = row.getName();
            item.latitude = row.getLatitude();
            item.longitude = row.getLongitude();
            item.address = row.getAddress();
            item.cover = row.getCover();
            item.npcAvatar = row.getNpcAvatar();
            item.poiType = row.getPoiType();
            item.found = row.getFound();
            item.pendingRedeem = row.getPendingRedeem();
            item.businessStatus = row.getBusinessStatus();
            return item;
        }

        public String getSourceType() { return sourceType; }
        public void setSourceType(String sourceType) { this.sourceType = sourceType; }
        public Long getSourceId() { return sourceId; }
        public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public BigDecimal getLatitude() { return latitude; }
        public void setLatitude(BigDecimal latitude) { this.latitude = latitude; }
        public BigDecimal getLongitude() { return longitude; }
        public void setLongitude(BigDecimal longitude) { this.longitude = longitude; }
        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
        public String getCover() { return cover; }
        public void setCover(String cover) { this.cover = cover; }
        public String getNpcAvatar() { return npcAvatar; }
        public void setNpcAvatar(String npcAvatar) { this.npcAvatar = npcAvatar; }
        public Integer getPoiType() { return poiType; }
        public void setPoiType(Integer poiType) { this.poiType = poiType; }
        public Boolean getFound() { return found; }
        public void setFound(Boolean found) { this.found = found; }
        public Boolean getPendingRedeem() { return pendingRedeem; }
        public void setPendingRedeem(Boolean pendingRedeem) { this.pendingRedeem = pendingRedeem; }
        public Integer getBusinessStatus() { return businessStatus; }
        public void setBusinessStatus(Integer businessStatus) { this.businessStatus = businessStatus; }
    }

    public static class Layer {
        private String sourceType;
        private String status;
        private String reason;
        private Capabilities capabilities = new Capabilities();
        private List<Item> items = new ArrayList<>();

        public String getSourceType() { return sourceType; }
        public void setSourceType(String sourceType) { this.sourceType = sourceType; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
        public Capabilities getCapabilities() { return capabilities; }
        public void setCapabilities(Capabilities capabilities) { this.capabilities = capabilities; }
        public List<Item> getItems() { return items; }
        public void setItems(List<Item> items) { this.items = items; }
    }
}
