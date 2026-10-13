-- R3 官方内容冷启动 · 种子模板(结构示例,非真实内容)
-- 背景:线上官方路线仅 3 条 demo,点亮城市 type=1 地标 POI 为 0。商业化首城需 5–10 条官方路线 + 数百地标 POI。
-- 用法:运营/内容团队按真实城市点位替换下方占位值后执行;坐标为 gcj02(腾讯/高德)。
-- 注意:cms_topic / cms_topic_node 列名以线上表结构为准(见 bak_cms_topic_schema_*.sql);此处仅示范字段与最小集。

-- 【A】官方线性路线(city 定向)—— topic_type=1 平台主题,mode 由票种 OmsTicket.mode 决定
-- INSERT INTO cms_topic (name, topic_type, status, city_code, cover, description, create_time)
-- VALUES ('外滩万国建筑寻踪', 1, 1, '310100', '<cover_url>', '沿外滩解锁 8 处历史建筑', NOW());
-- 取回 @topicId,再插 8–12 个节点:
-- INSERT INTO cms_topic_node (topic_id, name, address, sort_id, longitude, latitude, status, template_id, create_time)
-- VALUES (@topicId, '和平饭店', '南京东路20号', 1, 121.4900, 31.2400, 1, <模板id或NULL>, NOW()), ... ;

-- 【B】点亮城市地标 POI(roam_poi type=1)—— 地图冷启动的"可点亮点"
-- INSERT INTO roam_poi (name, type, lat, lng, radius_m, xp, city_code, status, create_time)
-- VALUES ('武康大楼', 1, 31.2100, 121.4350, 120, 20, '310100', 1, NOW()),
--        ('豫园',     1, 31.2270, 121.4920, 120, 20, '310100', 1, NOW()), ... ;

-- 建议:首城先灌 5 条精品路线(每条 8–12 站,真实走测校准动线/时长)+ 200+ 地标 POI。
-- 精品路线的选点/题目/叙事经真实完课率打磨后,即成不可复制的内容壁垒(见桌面商业化评估报告)。
