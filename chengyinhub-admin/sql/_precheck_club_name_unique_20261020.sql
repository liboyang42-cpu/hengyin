-- 只读预检:migration_club_name_unique_20261020.sql 落库前先跑,必须不返回任何重复组。
-- 口径与索引表达式完全相同:del_flag=0 且 status in (0,1) 的俱乐部按 TRIM(name)(列排序规则)占用名称。
-- 不修改任何数据;有返回行就停,交用户另行批准数据处置方案,不得直接跑迁移。
SELECT VERSION() AS mysql_version;

SELECT (CASE WHEN del_flag = 0 AND status IN (0, 1) THEN TRIM(name) END) AS holding_name,
       COUNT(*) AS holders,
       GROUP_CONCAT(CONCAT('id=', id, '/status=', status, '/owner=', member_id) ORDER BY id SEPARATOR '; ') AS clubs
FROM club
WHERE (CASE WHEN del_flag = 0 AND status IN (0, 1) THEN TRIM(name) END) IS NOT NULL
GROUP BY (CASE WHEN del_flag = 0 AND status IN (0, 1) THEN TRIM(name) END)
HAVING COUNT(*) > 1
ORDER BY holders DESC, holding_name;
