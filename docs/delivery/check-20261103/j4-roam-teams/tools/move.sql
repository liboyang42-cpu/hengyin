-- FIXTURE: shop-day topic 990014 -> 城市定向 (product_type 1) and stations moved near People's Square, as the m2 lane did
update cms_topic set product_type=1 where id=990014;
update cms_topic_node set latitude=31.2318, longitude=121.4749 where id=71;
update cms_topic_node set latitude=31.2331, longitude=121.4766 where id=72;
update cms_topic_node set latitude=31.2342, longitude=121.4781 where id=73;
update cms_topic_node set latitude=31.2355, longitude=121.4795 where id=74;
update cms_topic set published_route_graph_json = replace(replace(replace(replace(replace(replace(replace(replace(published_route_graph_json,
 '"31.240000"','"31.231800"'),'"121.480000"','"121.474900"'),'"31.250000"','"31.233100"'),'"121.490000"','"121.476600"'),
 '"31.260000"','"31.234200"'),'"121.500000"','"121.478100"'),'"31.270000"','"31.235500"'),'"121.510000"','"121.479500"') where id=990014;
update cms_registration_scan set latitude=case latitude when 31.24 then 31.2318 when 31.25 then 31.2331 when 31.26 then 31.2342 when 31.27 then 31.2355 else latitude end,
 longitude=case longitude when 121.48 then 121.4749 when 121.49 then 121.4766 when 121.50 then 121.4781 when 121.51 then 121.4795 else longitude end;
