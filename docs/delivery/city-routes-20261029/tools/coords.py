"""WGS84 -> GCJ-02 转换与步行距离估算（标准公开算法，Krasovsky 1940 椭球）。

用法：python3 coords.py stations.json   # [{"id","wgs84":[lon,lat]}...]
输出每站 GCJ-02 与相邻站直线距离、×1.3 步行估算。
"""
import json, math, sys

A = 6378245.0
EE = 0.00669342162296594323


def _tlat(x, y):
    r = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * math.sqrt(abs(x))
    r += (20.0 * math.sin(6.0 * x * math.pi) + 20.0 * math.sin(2.0 * x * math.pi)) * 2.0 / 3.0
    r += (20.0 * math.sin(y * math.pi) + 40.0 * math.sin(y / 3.0 * math.pi)) * 2.0 / 3.0
    r += (160.0 * math.sin(y / 12.0 * math.pi) + 320 * math.sin(y * math.pi / 30.0)) * 2.0 / 3.0
    return r


def _tlon(x, y):
    r = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * math.sqrt(abs(x))
    r += (20.0 * math.sin(6.0 * x * math.pi) + 20.0 * math.sin(2.0 * x * math.pi)) * 2.0 / 3.0
    r += (20.0 * math.sin(x * math.pi) + 40.0 * math.sin(x / 3.0 * math.pi)) * 2.0 / 3.0
    r += (150.0 * math.sin(x / 12.0 * math.pi) + 300.0 * math.sin(x / 30.0 * math.pi)) * 2.0 / 3.0
    return r


def wgs84_to_gcj02(lon, lat):
    dlat = _tlat(lon - 105.0, lat - 35.0)
    dlon = _tlon(lon - 105.0, lat - 35.0)
    rad = lat / 180.0 * math.pi
    magic = 1 - EE * math.sin(rad) ** 2
    sq = math.sqrt(magic)
    dlat = (dlat * 180.0) / ((A * (1 - EE)) / (magic * sq) * math.pi)
    dlon = (dlon * 180.0) / (A / sq * math.cos(rad) * math.pi)
    return round(lon + dlon, 6), round(lat + dlat, 6)


def haversine(a, b):
    lon1, lat1, lon2, lat2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
    return 2 * 6371008.8 * math.asin(math.sqrt(h))


if __name__ == "__main__":
    # 自检：参考线路和平饭店北楼 WGS84 121.48508,31.24108 → GCJ-02 121.489565,31.23911
    assert wgs84_to_gcj02(121.48508, 31.24108) == (121.489565, 31.23911)
    if len(sys.argv) > 1:
        st = json.load(open(sys.argv[1], encoding="utf-8"))
        prev, total = None, 0.0
        for s in st:
            g = wgs84_to_gcj02(*s["wgs84"])
            d = haversine(prev, s["wgs84"]) if prev else 0.0
            total += d * 1.3
            print(f'{s["id"]}\tGCJ02 {g[0]:.6f},{g[1]:.6f}\tline {d:.0f} m\twalk≈{d*1.3:.0f} m\tcum≈{total:.0f} m')
            prev = s["wgs84"]
