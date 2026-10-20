-- PRE_DEPLOY: missing standard RuoYi task dictionaries only; preserve every existing row.
-- Source: sql/ry_20250522.sql lines 467-468 and 506-509. Never replay the full legacy seed.
-- The two persistent tables are bootstrap prerequisites; no role, menu, account or job writes.
-- Match dict_type with its column collation, exactly as SysDictDataMapper does.
-- Keep dict_value byte-exact: the UI compares each standard value strictly.
-- runner: requires=transaction
DROP TEMPORARY TABLE IF EXISTS tmp_cy_job_dict_guard;
CREATE TEMPORARY TABLE tmp_cy_job_dict_guard(id INT PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO tmp_cy_job_dict_guard(id) VALUES(1);
INSERT INTO tmp_cy_job_dict_guard(id) SELECT 1 WHERE EXISTS (
 SELECT 1 FROM sys_dict_type
 WHERE dict_type IN ('sys_job_status','sys_job_group')
 GROUP BY dict_type HAVING COUNT(*)>1);
INSERT INTO tmp_cy_job_dict_guard(id) SELECT 1 WHERE EXISTS (
 SELECT 1 FROM sys_dict_data
 WHERE (dict_type='sys_job_status' AND CAST(dict_value AS BINARY) IN (CAST('0' AS BINARY),CAST('1' AS BINARY)))
    OR (dict_type='sys_job_group' AND CAST(dict_value AS BINARY) IN (CAST('DEFAULT' AS BINARY),CAST('SYSTEM' AS BINARY)))
 GROUP BY dict_type,CAST(dict_value AS BINARY) HAVING COUNT(*)>1);

INSERT INTO sys_dict_type(dict_name,dict_type,status,create_by,create_time,update_by,update_time,remark)
SELECT '任务状态','sys_job_status','0','migration',NOW(),'',NULL,'任务状态列表'
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type='sys_job_status');
INSERT INTO sys_dict_type(dict_name,dict_type,status,create_by,create_time,update_by,update_time,remark)
SELECT '任务分组','sys_job_group','0','migration',NOW(),'',NULL,'任务分组列表'
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type='sys_job_group');

INSERT INTO sys_dict_data(dict_sort,dict_label,dict_value,dict_type,css_class,list_class,is_default,status,create_by,create_time,update_by,update_time,remark)
SELECT 1,'正常','0','sys_job_status','','primary','Y','0','migration',NOW(),'',NULL,'正常状态'
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='sys_job_status' AND CAST(dict_value AS BINARY)=CAST('0' AS BINARY));
INSERT INTO sys_dict_data(dict_sort,dict_label,dict_value,dict_type,css_class,list_class,is_default,status,create_by,create_time,update_by,update_time,remark)
SELECT 2,'暂停','1','sys_job_status','','danger','N','0','migration',NOW(),'',NULL,'停用状态'
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='sys_job_status' AND CAST(dict_value AS BINARY)=CAST('1' AS BINARY));
INSERT INTO sys_dict_data(dict_sort,dict_label,dict_value,dict_type,css_class,list_class,is_default,status,create_by,create_time,update_by,update_time,remark)
SELECT 1,'默认','DEFAULT','sys_job_group','','','Y','0','migration',NOW(),'',NULL,'默认分组'
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='sys_job_group' AND CAST(dict_value AS BINARY)=CAST('DEFAULT' AS BINARY));
INSERT INTO sys_dict_data(dict_sort,dict_label,dict_value,dict_type,css_class,list_class,is_default,status,create_by,create_time,update_by,update_time,remark)
SELECT 2,'系统','SYSTEM','sys_job_group','','','N','0','migration',NOW(),'',NULL,'系统分组'
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='sys_job_group' AND CAST(dict_value AS BINARY)=CAST('SYSTEM' AS BINARY));

INSERT INTO tmp_cy_job_dict_guard(id) SELECT 1 WHERE
 (SELECT COUNT(*) FROM sys_dict_type WHERE dict_type IN ('sys_job_status','sys_job_group'))<>2
 OR (SELECT COUNT(*) FROM sys_dict_data WHERE
      (dict_type='sys_job_status' AND CAST(dict_value AS BINARY) IN (CAST('0' AS BINARY),CAST('1' AS BINARY)))
   OR (dict_type='sys_job_group' AND CAST(dict_value AS BINARY) IN (CAST('DEFAULT' AS BINARY),CAST('SYSTEM' AS BINARY))))<>4;
DROP TEMPORARY TABLE tmp_cy_job_dict_guard;
