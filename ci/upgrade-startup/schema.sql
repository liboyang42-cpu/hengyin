-- Disposable startup fixture only: no production DDL/seed, no business rows/jobs.
CREATE TABLE sys_config (
 config_id BIGINT PRIMARY KEY, config_name VARCHAR(100), config_key VARCHAR(100),
 config_value VARCHAR(500), config_type CHAR(1), create_by VARCHAR(64), create_time DATETIME,
 update_by VARCHAR(64), update_time DATETIME, remark VARCHAR(500)
);
CREATE TABLE sys_dict_data (
 dict_code BIGINT PRIMARY KEY, dict_sort INT, dict_label VARCHAR(100), dict_value VARCHAR(100),
 dict_type VARCHAR(100), css_class VARCHAR(100), list_class VARCHAR(100), is_default CHAR(1),
 status CHAR(1), create_by VARCHAR(64), create_time DATETIME, remark VARCHAR(500)
);
INSERT INTO sys_config(config_id,config_name,config_key,config_value,config_type)
 VALUES(1,'Synthetic startup probe','startup.synthetic.probe','cache-roundtrip','N');
INSERT INTO sys_dict_data(dict_code,dict_sort,dict_label,dict_value,dict_type,status)
 VALUES(1,1,'Synthetic','one','startup_synthetic','0');
