#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "用法: bash ci/verify-playpack-batch2-readback.sh <application-druid.yml>" >&2
  exit 64
}

[[ $# -eq 1 ]] || usage
[[ -n "$1" && "$1" != -* ]] || usage
[[ -f "$1" ]] || {
  echo "配置文件不存在或不是普通文件: $1" >&2
  exit 66
}

SCRIPT_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -P "$SCRIPT_DIR/.." && pwd)"
GIT_ROOT="$(git -C "$REPO_ROOT" rev-parse --show-toplevel 2>/dev/null || true)"
GIT_ROOT="$(cd -P "$GIT_ROOT" 2>/dev/null && pwd || true)"

[[ -n "$GIT_ROOT" && "$GIT_ROOT" == "$REPO_ROOT" && "$SCRIPT_DIR" == "$REPO_ROOT/ci" ]] || {
  echo "仓库路径校验失败，拒绝使用漂移的 runner" >&2
  exit 78
}

RUNNER="$REPO_ROOT/ci/run_migration.py"
[[ -f "$RUNNER" ]] || {
  echo "迁移 runner 不存在: $RUNNER" >&2
  exit 78
}

CONFIG_DIR="$(cd -P "$(dirname "$1")" && pwd)"
CONFIG_PATH="$CONFIG_DIR/$(basename "$1")"

# 本脚本只应在 migration 已提交并退出后单独启动；runner 因此会建立新连接回读业务表。
READBACK_SQL=$(cat <<'SQL'
-- runner: assert=scalar-true
SELECT
  (SELECT COUNT(*)
     FROM cms_topic
    WHERE create_by = 'seed_playpack2_20260821') = 8
  AND (SELECT COUNT(*)
         FROM cms_topic_chapter
        WHERE create_by = 'seed_playpack2_20260821') = 8
  AND (SELECT COUNT(*)
         FROM cms_member_template
        WHERE create_by = 'seed_playpack2_20260821') = 10
  AND (SELECT COUNT(*)
         FROM cms_topic_node
        WHERE create_by = 'seed_playpack2_20260821') = 10
  AND (SELECT COUNT(*)
         FROM product_role r
         JOIN cms_topic t ON t.id = r.topic_id
        WHERE t.create_by = 'seed_playpack2_20260821'
          AND r.slot = 'CONTENT'
          AND r.entity_type = 'PLATFORM'
          AND r.entity_id IS NULL
          AND r.status = 1) = 8
  AND NOT EXISTS (
    SELECT 1
      FROM (
        SELECT '闭眼味觉师' AS topic_name, '盲品席' AS chapter_name, 1 AS expected_nodes
        UNION ALL SELECT '菜单顺口溜', '贯口台', 1
        UNION ALL SELECT '沉默点单', '默剧场', 1
        UNION ALL SELECT '便利店调酒师', '调配台', 1
        UNION ALL SELECT '逆向NPC', '胡话局', 1
        UNION ALL SELECT '爪印巡街', '爪印册', 3
        UNION ALL SELECT '治愈音乐角', '声音角落', 1
        UNION ALL SELECT '穿搭盲配', '盲盒衣柜', 1
      ) expected
     WHERE (SELECT COUNT(*)
              FROM cms_topic t
             WHERE t.create_by = 'seed_playpack2_20260821'
               AND t.name = expected.topic_name
               AND t.member_id = 100000
               AND t.is_template = 1
               AND t.product_type = 2
               AND t.status = 1
               AND t.user_status = 0
               AND t.del_flag = 0) <> 1
        OR (SELECT COUNT(*)
              FROM cms_topic_chapter c
              JOIN cms_topic t ON t.id = c.topic_id
             WHERE t.create_by = 'seed_playpack2_20260821'
               AND t.name = expected.topic_name
               AND t.member_id = 100000
               AND t.del_flag = 0
               AND c.create_by = 'seed_playpack2_20260821'
               AND c.name = expected.chapter_name
               AND c.location_count = expected.expected_nodes
               AND c.template_count = expected.expected_nodes
               AND c.blocks IS NOT NULL
               AND JSON_VALID(c.blocks) = 1) <> 1
        OR (SELECT COUNT(*)
              FROM product_role r
              JOIN cms_topic t ON t.id = r.topic_id
             WHERE t.create_by = 'seed_playpack2_20260821'
               AND t.name = expected.topic_name
               AND t.member_id = 100000
               AND t.del_flag = 0
               AND r.slot = 'CONTENT'
               AND r.entity_type = 'PLATFORM'
               AND r.entity_id IS NULL
               AND r.status = 1) <> 1
  )
  AND NOT EXISTS (
    SELECT 1
      FROM (
        SELECT '闭眼味觉师' AS topic_name, '盲品一口' AS node_name,
               '玩法包2·盲品猜口味' AS template_title, 1 AS sort_value, 3 AS expected_vm
        UNION ALL SELECT '菜单顺口溜', '背出贯口', '玩法包2·顺口溜见证照', 1, 2
        UNION ALL SELECT '沉默点单', '无声点单', '玩法包2·哑剧接头', 1, 4
        UNION ALL SELECT '便利店调酒师', '调一杯上桌', '玩法包2·特调作品照', 1, 2
        UNION ALL SELECT '逆向NPC', '听懂胡话', '玩法包2·胡话推理', 1, 1
        UNION ALL SELECT '爪印巡街', '第一枚爪印', '玩法包2·爪印第一枚', 1, 2
        UNION ALL SELECT '爪印巡街', '第二枚爪印', '玩法包2·爪印第二枚', 2, 2
        UNION ALL SELECT '爪印巡街', '第三枚爪印', '玩法包2·爪印第三枚', 3, 2
        UNION ALL SELECT '治愈音乐角', '走到角落', '玩法包2·声音坐标', 1, 5
        UNION ALL SELECT '穿搭盲配', '盲配上身', '玩法包2·盲配造型照', 1, 2
      ) expected
     WHERE (SELECT COUNT(*)
              FROM cms_topic_node n
              JOIN cms_topic t ON t.id = n.topic_id
              JOIN cms_topic_chapter c ON c.id = n.chapter_id AND c.topic_id = t.id
              JOIN cms_member_template mt ON mt.id = n.template_id
             WHERE t.create_by = 'seed_playpack2_20260821'
               AND t.name = expected.topic_name
               AND t.member_id = 100000
               AND t.del_flag = 0
               AND c.create_by = 'seed_playpack2_20260821'
               AND JSON_VALID(c.blocks) = 1
               AND LOCATE(CONCAT('"nodeId":', n.id), c.blocks) > 0
               AND n.create_by = 'seed_playpack2_20260821'
               AND n.name = expected.node_name
               AND n.sort_id = expected.sort_value
               AND n.status = 1
               AND n.xp_value = 10
               AND mt.create_by = 'seed_playpack2_20260821'
               AND mt.title = expected.template_title
               AND mt.member_id = 100000
               AND mt.status = 1
               AND mt.draft_status = 1
               AND mt.del_flag = 0
               AND mt.validation_method = expected.expected_vm) <> 1
  ) AS assertion_ok
SQL
)

exec python3 "$RUNNER" --config "$CONFIG_PATH" --exec "$READBACK_SQL"
