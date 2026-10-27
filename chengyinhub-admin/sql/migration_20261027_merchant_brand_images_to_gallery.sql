-- M-31(2026-10-27 真机走查 m701):品牌形象图和门店相册是同一个东西,注册时的照片都放进门店相册。
-- 一次性数据迁移:把入驻时上传的品牌形象图(mms_merchant.derivatives,逗号/分号分隔的图片地址)
-- 追加进门店相册(mms_merchant.gallery,JSON 字符串数组)。
--   * 只追加相册里还没有的图,保持入驻时的顺序;相册最多 9 张(与门店相册页上限一致),超出的不追加;
--   * derivatives 原值保留不动;含引号/反斜杠(拼不成 JSON)的行跳过;gallery 既不是 NULL/空串、也不是合法 JSON 数组的行一律不碰;
--   * 重放幂等:第二次执行时这些图都已在相册里(或相册已满),不再改动任何行。
-- 图片地址只搬运、不改写:读时仍按 app_media_asset 登记事实 / 本站存储白名单投影成可展示地址。
-- 依赖:migration_merchant_decor_20260708.sql(gallery 列)。

UPDATE mms_merchant m
JOIN (
    SELECT DISTINCT ranked.id,
           JSON_ARRAYAGG(ranked.url) OVER (
               PARTITION BY ranked.id ORDER BY ranked.ord
               ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
           ) AS extra
    FROM (
        SELECT fresh.id, fresh.url, fresh.ord, fresh.existing,
               ROW_NUMBER() OVER (PARTITION BY fresh.id ORDER BY fresh.ord) AS pos
        FROM (
            SELECT brand.id, brand.url, MIN(brand.ord) AS ord, MAX(brand.existing) AS existing
            FROM (
                SELECT mm.id, TRIM(image.url) AS url, image.ord,
                       IF(mm.gallery IS NULL OR TRIM(mm.gallery) = '', 0, JSON_LENGTH(mm.gallery)) AS existing,
                       IF(mm.gallery IS NULL OR TRIM(mm.gallery) = '', 0,
                          JSON_CONTAINS(mm.gallery, JSON_QUOTE(TRIM(image.url)))) AS present
                FROM mms_merchant mm
                CROSS JOIN JSON_TABLE(
                    -- 含引号/反斜杠的地址拼不成合法 JSON 数组(也不是本站签发的图片地址):该行按空数组处理。
                    IF(LOCATE('"', mm.derivatives) = 0 AND LOCATE(CHAR(92), mm.derivatives) = 0,
                       CONCAT('["', REPLACE(REPLACE(TRIM(mm.derivatives), ';', ','), ',', '","'), '"]'), '[]'),
                    '$[*]' COLUMNS (
                        ord FOR ORDINALITY,
                        url VARCHAR(1000) PATH '$'
                    )
                ) image
                WHERE mm.derivatives IS NOT NULL AND TRIM(mm.derivatives) <> ''
                  AND (mm.gallery IS NULL OR TRIM(mm.gallery) = ''
                       OR (JSON_VALID(mm.gallery) AND JSON_TYPE(mm.gallery) = 'ARRAY'))
            ) brand
            WHERE brand.url <> '' AND brand.present = 0
            GROUP BY brand.id, brand.url
        ) fresh
    ) ranked
    WHERE ranked.existing + ranked.pos <= 9
) brand_images ON brand_images.id = m.id
SET m.gallery = IF(m.gallery IS NULL OR TRIM(m.gallery) = '',
                   CAST(brand_images.extra AS CHAR),
                   CAST(JSON_MERGE_PRESERVE(CAST(m.gallery AS JSON), brand_images.extra) AS CHAR))
WHERE m.derivatives IS NOT NULL AND TRIM(m.derivatives) <> '';
