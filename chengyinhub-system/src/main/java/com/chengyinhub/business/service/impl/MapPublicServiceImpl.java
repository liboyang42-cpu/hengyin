package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.map.MapPublicCursor;
import com.chengyinhub.business.domain.map.MapPublicException;
import com.chengyinhub.business.domain.map.MapPublicItemFacts;
import com.chengyinhub.business.domain.map.MapPublicLayerFailure;
import com.chengyinhub.business.domain.map.MapPublicLimits;
import com.chengyinhub.business.domain.map.MapPublicQuery;
import com.chengyinhub.business.domain.map.MapPublicResult;
import com.chengyinhub.business.domain.map.MapPublicRow;
import com.chengyinhub.business.service.MapPublicService;
import com.chengyinhub.business.service.map.MapPublicSourceAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MapPublicServiceImpl implements MapPublicService {

    private final Map<String, MapPublicSourceAdapter> adapters;

    @Autowired
    public MapPublicServiceImpl(List<MapPublicSourceAdapter> adapters) {
        if (adapters == null || adapters.isEmpty()) {
            throw new IllegalArgumentException("NO_SOURCES");
        }
        Map<String, MapPublicSourceAdapter> map = new LinkedHashMap<>();
        for (MapPublicSourceAdapter adapter : adapters) {
            if (adapter == null || adapter.sourceType() == null || map.put(adapter.sourceType(), adapter) != null) {
                throw new IllegalArgumentException("DUPLICATE_SOURCE");
            }
        }
        this.adapters = Collections.unmodifiableMap(map);
    }

    @Override
    public MapPublicResult query(long viewerId, MapPublicQuery query) {
        if (viewerId <= 0) {
            throw new MapPublicException("VIEWER_REJECTED");
        }
        if (query == null) {
            throw new MapPublicException("QUERY_REJECTED");
        }
        MapPublicQuery.Normalized normalized = query.normalize();
        List<MapPublicSourceAdapter> ordered = new ArrayList<>();
        for (String name : normalized.layers()) {
            MapPublicSourceAdapter adapter = adapters.get(name);
            if (adapter == null) {
                throw new MapPublicException("LAYERS_REJECTED");
            }
            ordered.add(adapter);
        }
        MapPublicCursor.Parsed cursor = null;
        if (query.getCursor() != null) {
            cursor = MapPublicCursor.parse(query.getCursor());
            if (cursor.viewerId() != viewerId
                    || !MapPublicLimits.WATERMARK.equals(cursor.watermark())
                    || !normalized.fingerprint().equals(cursor.fingerprint())) {
                throw new MapPublicException("CURSOR_REJECTED");
            }
            requirePositions(cursor, ordered);
        }

        boolean failed = false;
        boolean more = false;
        List<MapPublicResult.Layer> layers = new ArrayList<>();
        List<MapPublicCursor.Pos> next = new ArrayList<>();
        for (MapPublicSourceAdapter adapter : ordered) {
            MapPublicResult.Layer layer = newLayer(adapter);
            if (!adapter.available()) {
                layer.setStatus("UNAVAILABLE");
                layer.setReason(adapter.unavailableReason());
                layer.setItems(List.of());
                layers.add(layer);
                continue;
            }
            MapPublicCursor.Pos pos = cursor == null ? null : cursor.position(adapter.sourceType());
            if (pos != null && pos.exhausted()) {
                layer.setStatus("OK");
                layer.setItems(List.of());
                layers.add(layer);
                next.add(MapPublicCursor.Pos.exhausted(adapter.sourceType()));
                continue;
            }
            try {
                List<MapPublicRow> rows = adapter.page(viewerId, normalized.minLat(), normalized.maxLat(),
                        normalized.minLng(), normalized.maxLng(),
                        pos == null ? null : pos.afterId(), normalized.limit());
                if (rows == null) {
                    rows = List.of();
                }
                List<MapPublicResult.Item> items = items(adapter.sourceType(), rows, pos);
                layer.setStatus("OK");
                layer.setItems(items);
                if (items.size() < normalized.limit()) {
                    next.add(MapPublicCursor.Pos.exhausted(adapter.sourceType()));
                } else {
                    more = true;
                    next.add(MapPublicCursor.Pos.after(adapter.sourceType(), items.get(items.size() - 1).getSourceId()));
                }
            } catch (MapPublicLayerFailure ex) {
                failed = true;
                layer.setStatus("FAILED");
                layer.setReason(ex.getMessage());
                layer.setItems(List.of());
            } catch (MapPublicException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                failed = true;
                layer.setStatus("FAILED");
                layer.setReason("SOURCE_FAILED");
                layer.setItems(List.of());
            }
            layers.add(layer);
        }

        MapPublicResult result = new MapPublicResult();
        result.setDatum(MapPublicLimits.DATUM);
        result.setAggregatedQueryEnabled(false);
        result.setCluster(MapPublicResult.Cluster.unsupported());
        result.setLayers(layers);
        if (!failed && more) {
            result.setNextCursor(MapPublicCursor.encode(normalized, viewerId, next));
        }
        return result;
    }

    private static void requirePositions(MapPublicCursor.Parsed cursor, List<MapPublicSourceAdapter> ordered) {
        List<String> available = new ArrayList<>();
        for (MapPublicSourceAdapter adapter : ordered) {
            if (adapter.available()) {
                available.add(adapter.sourceType());
            }
        }
        if (cursor.positions().size() != available.size()) {
            throw new MapPublicException("CURSOR_REJECTED");
        }
        for (int i = 0; i < available.size(); i++) {
            if (!available.get(i).equals(cursor.positions().get(i).sourceType())) {
                throw new MapPublicException("CURSOR_REJECTED");
            }
        }
    }

    private static MapPublicResult.Layer newLayer(MapPublicSourceAdapter adapter) {
        MapPublicResult.Layer layer = new MapPublicResult.Layer();
        layer.setSourceType(adapter.sourceType());
        MapPublicResult.Capabilities capabilities = new MapPublicResult.Capabilities();
        capabilities.setRead(adapter.available());
        capabilities.setCount(false);
        capabilities.setCluster(false);
        layer.setCapabilities(capabilities);
        return layer;
    }

    private static List<MapPublicResult.Item> items(String sourceType, List<MapPublicRow> rows, MapPublicCursor.Pos pos) {
        List<MapPublicResult.Item> items = new ArrayList<>();
        long previous = pos == null || pos.afterId() == null ? Long.MIN_VALUE : pos.afterId();
        for (MapPublicRow row : rows) {
            if (row == null || row.getSourceId() == null || !sourceType.equals(row.getSourceType())
                    || row.getSourceId() <= previous) {
                throw new IllegalStateException("source key");
            }
            previous = row.getSourceId();
            items.add(item(row));
        }
        return items;
    }

    private static MapPublicResult.Item item(MapPublicRow row) {
        String version = MapPublicItemFacts.visibilityVersion(row);
        String state = MapPublicItemFacts.visualState(row);
        List<String> actions = MapPublicItemFacts.actions(row);
        if (version == null) {
            throw new MapPublicLayerFailure("VISIBILITY_VERSION_ABSENT");
        }
        if (state == null || "available".equals(state)) {
            throw new MapPublicLayerFailure("VISUAL_STATE_ABSENT");
        }
        if (actions.contains(MapPublicItemFacts.REGISTER)) {
            throw new MapPublicLayerFailure("ACTION_REJECTED");
        }
        MapPublicResult.Item item = MapPublicResult.Item.from(row);
        item.setVisibilityVersion(version);
        item.setVisualState(state);
        item.setActions(actions);
        return item;
    }
}
