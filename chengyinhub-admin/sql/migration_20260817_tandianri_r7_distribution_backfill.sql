-- 探店日 R7：为可由冻结事实唯一确定的存量期次补登 DISTRIBUTION。
-- 【类别】③ 要在库上跑（存量审计事实补登），必须先于包含 DISTRIBUTION 硬闸的 jar。
-- 【前置】migration_20260816_tandianri_batch3_funds_club_compensation.sql；
--          migration_20260815_shop_day_organizer_actor.sql。后者的依赖链已包含 product_role 建表与
--          declared_by 审计列，缺任一前置都不得执行本文件。
-- 【幂等】只补「冻结 terms 已唯一给出 executing_club_id、唯一合法 ORGANIZER 已给出 declared_by、
--          且当前没有生效 DISTRIBUTION」的主题；同文件重放不会再插一行。
-- 【可逆】禁止按条件批量删除业务角色。若必须回退，先回退读取该槽的 jar，并只按本次执行清单记录的
--          product_role.id 逐行置为失效；已有售票/FINAL/应付的期次不得回退该审计事实。
-- ★不要把缺槽降回 0 元，也不要从 cms_topic.club_id、活动壳或报名表猜主体。

-- 写前 fail-closed：每个已开售且有冻结 terms 的探店日期次必须满足：
--   ① 恰好一个合法的生效 ORGANIZER 审计 actor；
--   ② 生效 DISTRIBUTION 至多一行；若已存在，只能与 terms + ORGANIZER 完全一致。
-- 返回 0 时 runner 必须停止，绝不挑一行、删一行或继续部分补登。
-- runner: assert=scalar-true
SELECT COUNT(*) = 0
FROM cms_topic topic
JOIN club_edition_compensation_terms terms ON terms.topic_id = topic.id
WHERE topic.product_type = 2
  AND topic.del_flag <> 2
  AND topic.lifecycle >= 3
  AND topic.selling_started_at IS NOT NULL
  AND terms.executing_club_id IS NOT NULL
  AND terms.executing_club_id > 0
  AND (
      (SELECT COUNT(*)
         FROM product_role organizer
        WHERE organizer.topic_id = topic.id
          AND organizer.slot = 'ORGANIZER'
          AND organizer.entity_type = 'PLATFORM'
          AND organizer.entity_id IS NULL
          AND organizer.status = 1
          AND organizer.declared_by IS NOT NULL
          AND organizer.declared_by > 0) <> 1
      OR
      (SELECT COUNT(*)
         FROM product_role distribution
        WHERE distribution.topic_id = topic.id
          AND distribution.slot = 'DISTRIBUTION'
          AND distribution.status = 1) > 1
      OR EXISTS (
          SELECT 1
          FROM product_role distribution
          JOIN product_role organizer
            ON organizer.topic_id = distribution.topic_id
           AND organizer.slot = 'ORGANIZER'
           AND organizer.entity_type = 'PLATFORM'
           AND organizer.entity_id IS NULL
           AND organizer.status = 1
           AND organizer.declared_by IS NOT NULL
           AND organizer.declared_by > 0
          WHERE distribution.topic_id = topic.id
            AND distribution.slot = 'DISTRIBUTION'
            AND distribution.status = 1
            AND (distribution.entity_type <> 'CLUB'
                 OR distribution.entity_id IS NULL
                 OR distribution.entity_id <> terms.executing_club_id
                 OR distribution.declared_by IS NULL
                 OR distribution.declared_by <> organizer.declared_by)
      )
  );

INSERT INTO product_role
    (topic_id, slot, entity_type, entity_id, revenue_share, status, declared_by)
SELECT topic.id, 'DISTRIBUTION', 'CLUB', terms.executing_club_id, NULL, 1,
       organizer.declared_by
FROM cms_topic topic
JOIN club_edition_compensation_terms terms ON terms.topic_id = topic.id
JOIN product_role organizer
  ON organizer.topic_id = topic.id
 AND organizer.slot = 'ORGANIZER'
 AND organizer.entity_type = 'PLATFORM'
 AND organizer.entity_id IS NULL
 AND organizer.status = 1
 AND organizer.declared_by IS NOT NULL
 AND organizer.declared_by > 0
WHERE topic.product_type = 2
  AND topic.del_flag <> 2
  AND topic.lifecycle >= 3
  AND topic.selling_started_at IS NOT NULL
  AND terms.executing_club_id IS NOT NULL
  AND terms.executing_club_id > 0
  AND (SELECT COUNT(*)
         FROM product_role organizer_count
        WHERE organizer_count.topic_id = topic.id
          AND organizer_count.slot = 'ORGANIZER'
          AND organizer_count.entity_type = 'PLATFORM'
          AND organizer_count.entity_id IS NULL
          AND organizer_count.status = 1
          AND organizer_count.declared_by IS NOT NULL
          AND organizer_count.declared_by > 0) = 1
  AND NOT EXISTS (
      SELECT 1
      FROM product_role distribution
      WHERE distribution.topic_id = topic.id
        AND distribution.slot = 'DISTRIBUTION'
        AND distribution.status = 1
  );

-- 末端 readback：问 product_role 自己是否已形成唯一、合法、与两份冻结事实一致的槽。
-- 任一遗漏、缺 actor、多义或冲突都返回 0，让 migration runner 变红。
-- runner: assert=scalar-true
SELECT COUNT(*) = 0
FROM cms_topic topic
JOIN club_edition_compensation_terms terms ON terms.topic_id = topic.id
WHERE topic.product_type = 2
  AND topic.del_flag <> 2
  AND topic.lifecycle >= 3
  AND topic.selling_started_at IS NOT NULL
  AND terms.executing_club_id IS NOT NULL
  AND terms.executing_club_id > 0
  AND (
      (SELECT COUNT(*)
         FROM product_role organizer
        WHERE organizer.topic_id = topic.id
          AND organizer.slot = 'ORGANIZER'
          AND organizer.entity_type = 'PLATFORM'
          AND organizer.entity_id IS NULL
          AND organizer.status = 1
          AND organizer.declared_by IS NOT NULL
          AND organizer.declared_by > 0) <> 1
      OR
      (SELECT COUNT(*)
         FROM product_role distribution
         JOIN product_role organizer
           ON organizer.topic_id = distribution.topic_id
          AND organizer.slot = 'ORGANIZER'
          AND organizer.entity_type = 'PLATFORM'
          AND organizer.entity_id IS NULL
          AND organizer.status = 1
          AND organizer.declared_by IS NOT NULL
          AND organizer.declared_by > 0
        WHERE distribution.topic_id = topic.id
          AND distribution.slot = 'DISTRIBUTION'
          AND distribution.entity_type = 'CLUB'
          AND distribution.entity_id = terms.executing_club_id
          AND distribution.status = 1
          AND distribution.declared_by = organizer.declared_by) <> 1
      OR EXISTS (
          SELECT 1
          FROM product_role distribution
          WHERE distribution.topic_id = topic.id
            AND distribution.slot = 'DISTRIBUTION'
            AND distribution.status = 1
            AND (distribution.entity_type <> 'CLUB'
                 OR distribution.entity_id IS NULL
                 OR distribution.entity_id <> terms.executing_club_id)
      )
  );
