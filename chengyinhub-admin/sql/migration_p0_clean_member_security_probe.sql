-- P0 security cleanup: remove Log4Shell/JNDI probe strings accidentally saved in member profile fields.
-- Safe to run multiple times.

UPDATE ums_member
SET work_address = '',
    update_time = NOW()
WHERE work_address LIKE '%${jndi:%'
   OR work_address LIKE '%ldap://%'
   OR work_address LIKE '%rmi://%';

UPDATE ums_member
SET introduction = '',
    update_time = NOW()
WHERE introduction LIKE '%${jndi:%'
   OR introduction LIKE '%ldap://%'
   OR introduction LIKE '%rmi://%';
