-- ============================================================
-- 建议版预设 演示数据(部署后即可在小程序建议版看到效果)
-- 依赖:先执行 migration_preset_route.sql(建表)
-- 幂等:用固定 name 去重(FROM dual WHERE NOT EXISTS),重复执行不产生重复行
-- 城市坐标为上海真实点位(高德/腾讯坐标系,小程序<map>通用)
-- ============================================================

-- ---------- 主题风格(4 个) ----------
INSERT INTO preset_theme_style (name, cover_img, tone_tokens, default_title, default_subtitle, default_desc, default_category_ids, sort, status, del_flag, create_by, create_time)
SELECT '深夜城市探险', 'https://xianghuioss.oss-cn-shanghai.aliyuncs.com/chenyin/map.png', '{"--cy-accent":"#1A1A1A"}',
  '深夜城市探险', '入夜后的城市,藏着另一副面孔',
  '夜幕降临,沿着街角线索完成一次城市探索:观察、问答、拍照、协作,最终拼出这片街区的隐藏故事。',
  '', 1, '1', 0, 'admin', NOW()
FROM dual WHERE NOT EXISTS (SELECT 1 FROM preset_theme_style WHERE name='深夜城市探险' AND del_flag=0);

INSERT INTO preset_theme_style (name, cover_img, tone_tokens, default_title, default_subtitle, default_desc, default_category_ids, sort, status, del_flag, create_by, create_time)
SELECT '咖啡漫游', 'https://xianghuioss.oss-cn-shanghai.aliyuncs.com/chenyin/map.png', NULL,
  '咖啡漫游', '一杯接一杯,把城市喝慢',
  '从一家咖啡馆出发,沿路打卡几处风格小店,边走边喝边记录,完成一次轻松的城市慢行。',
  '', 2, '1', 0, 'admin', NOW()
FROM dual WHERE NOT EXISTS (SELECT 1 FROM preset_theme_style WHERE name='咖啡漫游' AND del_flag=0);

INSERT INTO preset_theme_style (name, cover_img, tone_tokens, default_title, default_subtitle, default_desc, default_category_ids, sort, status, del_flag, create_by, create_time)
SELECT '复古胶片 Citywalk', 'https://xianghuioss.oss-cn-shanghai.aliyuncs.com/chenyin/map.png', NULL,
  '复古胶片 Citywalk', '用一卷胶片的耐心,走一段老街',
  '带上相机,沿老城风貌路线寻找有年代感的招牌与街角,完成几组主题拍照任务。',
  '', 3, '1', 0, 'admin', NOW()
FROM dual WHERE NOT EXISTS (SELECT 1 FROM preset_theme_style WHERE name='复古胶片 Citywalk' AND del_flag=0);

INSERT INTO preset_theme_style (name, cover_img, tone_tokens, default_title, default_subtitle, default_desc, default_category_ids, sort, status, del_flag, create_by, create_time)
SELECT '解谜街区', 'https://xianghuioss.oss-cn-shanghai.aliyuncs.com/chenyin/map.png', NULL,
  '解谜街区', '每个站点都是一道题',
  '沿路线逐站解谜,扫码打卡推进剧情,和同行的人一起在限定时间内通关。',
  '', 4, '1', 0, 'admin', NOW()
FROM dual WHERE NOT EXISTS (SELECT 1 FROM preset_theme_style WHERE name='解谜街区' AND del_flag=0);

-- ---------- 城市路线(3 条) + 站点 ----------

-- 路线1:外滩夜线
INSERT INTO preset_route (city, name, preview_img, route_geometry, total_distance, total_time, default_category_ids, sort, status, del_flag, create_by, create_time)
SELECT '上海', '外滩夜线', 'https://xianghuioss.oss-cn-shanghai.aliyuncs.com/chenyin/map.png',
  NULL, 2.40, 120, '', 1, '1', 0, 'admin', NOW()
FROM dual WHERE NOT EXISTS (SELECT 1 FROM preset_route WHERE city='上海' AND name='外滩夜线' AND del_flag=0);

INSERT INTO preset_route_node (route_id, name, address, longitude, latitude, img_url, node_time, sort, del_flag, create_by, create_time)
SELECT r.id, n.name, n.address, n.longitude, n.latitude, NULL, n.node_time, n.sort, 0, 'admin', NOW()
FROM preset_route r
JOIN (
  SELECT '外滩观景台' AS name, '上海市黄浦区中山东一路' AS address, '121.490317' AS longitude, '31.237536' AS latitude, 30 AS node_time, 0 AS sort
  UNION ALL SELECT '外白渡桥', '上海市黄浦区中山东一路北端', '121.487899', '31.245333', 25, 1
  UNION ALL SELECT '东方明珠对岸', '上海市黄浦区中山东二路', '121.498740', '31.240853', 30, 2
  UNION ALL SELECT '十六铺码头', '上海市黄浦区中山东二路501号', '121.499000', '31.222000', 35, 3
) n
WHERE r.city='上海' AND r.name='外滩夜线' AND r.del_flag=0
  AND NOT EXISTS (SELECT 1 FROM preset_route_node x WHERE x.route_id=r.id AND x.del_flag=0);

-- 路线2:武康路文艺线
INSERT INTO preset_route (city, name, preview_img, route_geometry, total_distance, total_time, default_category_ids, sort, status, del_flag, create_by, create_time)
SELECT '上海', '武康路文艺线', 'https://xianghuioss.oss-cn-shanghai.aliyuncs.com/chenyin/map.png',
  NULL, 1.80, 90, '', 2, '1', 0, 'admin', NOW()
FROM dual WHERE NOT EXISTS (SELECT 1 FROM preset_route WHERE city='上海' AND name='武康路文艺线' AND del_flag=0);

INSERT INTO preset_route_node (route_id, name, address, longitude, latitude, img_url, node_time, sort, del_flag, create_by, create_time)
SELECT r.id, n.name, n.address, n.longitude, n.latitude, NULL, n.node_time, n.sort, 0, 'admin', NOW()
FROM preset_route r
JOIN (
  SELECT '武康大楼' AS name, '上海市徐汇区淮海中路1850号' AS address, '121.435100' AS longitude, '31.205200' AS latitude, 30 AS node_time, 0 AS sort
  UNION ALL SELECT '巴金故居', '上海市徐汇区武康路113号', '121.432300', '31.202900', 25, 1
  UNION ALL SELECT '武康庭', '上海市徐汇区武康路376号', '121.430500', '31.208900', 35, 2
) n
WHERE r.city='上海' AND r.name='武康路文艺线' AND r.del_flag=0
  AND NOT EXISTS (SELECT 1 FROM preset_route_node x WHERE x.route_id=r.id AND x.del_flag=0);

-- 路线3:豫园老城线
INSERT INTO preset_route (city, name, preview_img, route_geometry, total_distance, total_time, default_category_ids, sort, status, del_flag, create_by, create_time)
SELECT '上海', '豫园老城线', 'https://xianghuioss.oss-cn-shanghai.aliyuncs.com/chenyin/map.png',
  NULL, 1.50, 80, '', 3, '1', 0, 'admin', NOW()
FROM dual WHERE NOT EXISTS (SELECT 1 FROM preset_route WHERE city='上海' AND name='豫园老城线' AND del_flag=0);

INSERT INTO preset_route_node (route_id, name, address, longitude, latitude, img_url, node_time, sort, del_flag, create_by, create_time)
SELECT r.id, n.name, n.address, n.longitude, n.latitude, NULL, n.node_time, n.sort, 0, 'admin', NOW()
FROM preset_route r
JOIN (
  SELECT '豫园九曲桥' AS name, '上海市黄浦区豫园老街' AS address, '121.492200' AS longitude, '31.227300' AS latitude, 30 AS node_time, 0 AS sort
  UNION ALL SELECT '城隍庙', '上海市黄浦区方浜中路249号', '121.491800', '31.226600', 30, 1
  UNION ALL SELECT '老城厢茶楼', '上海市黄浦区旧校场路', '121.493000', '31.228000', 20, 2
) n
WHERE r.city='上海' AND r.name='豫园老城线' AND r.del_flag=0
  AND NOT EXISTS (SELECT 1 FROM preset_route_node x WHERE x.route_id=r.id AND x.del_flag=0);
