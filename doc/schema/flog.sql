-- flog 林木采伐限额与采伐许可证管理 -- schema (chen-015)
-- 列名与基线实体契约（@TableName/@TableField）逐列对齐，改列必须同步实体。
-- 库：chen_015

CREATE TABLE IF NOT EXISTS t_flog_apply_card (
  id bigint NOT NULL COMMENT '主键',
  bill_no varchar(64) DEFAULT NULL COMMENT '伐区采伐申请建档卡号',
  node_no int DEFAULT NULL COMMENT '本卡版本序',
  site_id int DEFAULT NULL COMMENT '所属编制单位在册底档',
  site_no varchar(64) DEFAULT NULL COMMENT '所属单位底档字号',
  subcomp_no varchar(64) DEFAULT NULL COMMENT '小班号',
  cut_block_no varchar(64) DEFAULT NULL COMMENT '伐区名号',
  cut_mode int DEFAULT NULL COMMENT '采伐方式',
  clear_trees int DEFAULT NULL COMMENT '皆伐株数',
  current_flag int DEFAULT NULL COMMENT '版本现行标记 1现行 0历史',
  qty decimal(12,2) DEFAULT NULL COMMENT '本卡合并后拟采蓄积(立方米)',
  fine_amt decimal(12,2) DEFAULT NULL COMMENT '本卡新报拟采面积(公顷)',
  content varchar(255) DEFAULT NULL COMMENT '核验串（随版本序走）',
  grade_level int DEFAULT NULL COMMENT '拟采强度折到的取用顺位',
  status int DEFAULT NULL COMMENT '进展 0待核对 1已核对 2已压死',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id),
  -- 同一底档下，正常卡（del_flag=0）的建档卡号唯一；软删行键值为 NULL 不参与约束。
  -- 与 service 的 dupExists(site_id, bill_no, 排除自身) 同一口径：
  --   ① 全局唯一会误拦不同底档各自编号，必须收敛到同底档；
  --   ② 并发双击/存量导入绕过应用层时由数据库兜底；
  --   ③ 软删后卡号可重新使用。
  UNIQUE KEY uk_apply_card_site_bill_active
    ((CASE WHEN del_flag = 0 THEN site_id END), (CASE WHEN del_flag = 0 THEN bill_no END))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='伐区采伐申请建档卡';

CREATE TABLE IF NOT EXISTS t_flog_close_card (
  id bigint NOT NULL COMMENT '主键',
  biz_no varchar(64) DEFAULT NULL COMMENT '伐区勾销事务卡号',
  stage int DEFAULT NULL COMMENT '当前段次 0..3（报伐/核株/验更/了断）',
  status int DEFAULT NULL COMMENT '卡的落定 0未起 1在办 2已了断',
  content varchar(255) DEFAULT NULL COMMENT '勾销记事',
  last_action varchar(64) DEFAULT NULL COMMENT '最近一次过口动作',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='伐区勾销事务卡';

CREATE TABLE IF NOT EXISTS t_flog_declare_row (
  id bigint NOT NULL COMMENT '主键',
  batch_no varchar(64) DEFAULT NULL COMMENT '伐区申报清册批次号',
  row_no int DEFAULT NULL COMMENT '原清册行次',
  item_code varchar(64) DEFAULT NULL COMMENT '被打回的前置项代号',
  qty decimal(12,2) DEFAULT NULL COMMENT '本行申报拟采蓄积(立方米)',
  status int DEFAULT NULL COMMENT '行落地情形 0待核 1已收 2挂起',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='伐区申报清册核收行';

CREATE TABLE IF NOT EXISTS t_flog_permit_bill (
  id bigint NOT NULL COMMENT '主键',
  bill_no varchar(64) DEFAULT NULL COMMENT '采伐许可证核签单号',
  node_no int DEFAULT NULL COMMENT '当前所在级 0..3',
  sign_mode int DEFAULT NULL COMMENT '同级核签办法 0任一人 1两名点齐',
  need_count int DEFAULT NULL COMMENT '本级应签人数',
  sign_count int DEFAULT NULL COMMENT '本级已签人数',
  status int DEFAULT NULL COMMENT '报批情形 0在核 1已核讫 2已打回',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采伐许可证逐级核签单';

CREATE TABLE IF NOT EXISTS t_flog_quota_task (
  id bigint NOT NULL COMMENT '主键',
  item_no varchar(64) DEFAULT NULL COMMENT '额度事项号',
  due_at datetime DEFAULT NULL COMMENT '本桩事该动手那天的最后一刻',
  amount decimal(12,2) DEFAULT NULL COMMENT '开口前预先提醒的自然日数',
  content varchar(255) DEFAULT NULL COMMENT '事由与受文林场记要',
  status int DEFAULT NULL COMMENT '条目情形 0未开口 1已落地 2办不下去',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='年采伐限额年度执行额度下达与结余收回事项';

CREATE TABLE IF NOT EXISTS t_flog_stand_doc (
  id bigint NOT NULL COMMENT '主键',
  site_no varchar(64) DEFAULT NULL COMMENT '编制单位在册底档字号',
  site_name varchar(128) DEFAULT NULL COMMENT '编制单位全称',
  site_type varchar(32) DEFAULT NULL COMMENT '经营类别',
  road_name varchar(128) DEFAULT NULL COMMENT '管辖林业站(省—市—县)',
  th1_max decimal(12,2) DEFAULT NULL COMMENT '单卡拟采蓄积轻度线上限(立方米)',
  th2_max decimal(12,2) DEFAULT NULL COMMENT '中度线上限(立方米)',
  th3_max decimal(12,2) DEFAULT NULL COMMENT '重度线上限(立方米)',
  status int DEFAULT NULL COMMENT '底档情形 0在册 1已收回',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='编制单位在册底档';

CREATE TABLE IF NOT EXISTS t_flog_yield_rule (
  id bigint NOT NULL COMMENT '主键',
  rule_code varchar(64) DEFAULT NULL COMMENT '可采强度界限值代号',
  rule_name varchar(128) DEFAULT NULL COMMENT '线名',
  th1_max decimal(12,2) DEFAULT NULL COMMENT '轻度线上限(立方米/公顷)',
  th2_max decimal(12,2) DEFAULT NULL COMMENT '中度线上限(立方米/公顷)',
  th3_max decimal(12,2) DEFAULT NULL COMMENT '重度线上限(立方米/公顷)',
  eff_start datetime DEFAULT NULL COMMENT '启用之日',
  eff_end datetime DEFAULT NULL COMMENT '交棒之日(不含)',
  priority int DEFAULT NULL COMMENT '抢线顺位(数值越大越先开口)',
  status int DEFAULT NULL COMMENT '线的情形 0现行 1已让位',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='可采强度界限值线';

-- 初始底档数据（在册、已收回两类各一条）
-- t_flog_stand_doc 两条种子底档：id=0 在册、id=1 已收回。
-- 单卡拟采蓄积三线上限取 40/120/300 立方米（等于上限算高一档）。
INSERT IGNORE INTO t_flog_stand_doc (id, site_no, site_name, site_type, road_name, th1_max, th2_max, th3_max, status, del_flag, create_by, create_time)
VALUES (0, 'DC00', '青屏县编制单位在册底档（青屏国有林场，在册）', '国有林场', '青屏省—青屏市—青屏县', 40.00, 120.00, 300.00, 0, 0, 'seed', NOW()),
       (1, 'DC01', '青屏县编制单位在册底档（白石集体林场，已收回）', '集体林场', '青屏省—青屏市—青屏县', 40.00, 120.00, 300.00, 1, 0, 'seed', NOW());

-- ============================================================================
-- 存量数据对齐：老规矩与新规矩一个口径。
-- 顺序不可乱：先清洗 → 回填字号/档位 → 人工处理停用底档/重复/未挂钩 → 再建唯一索引 → 回溯经办人。
-- 全角字母/全角空格的归一在应用层搬入口（/FlogApplyCardController/importData）完成，
-- 纯 SQL 只处理首尾空白与大小写。
-- ============================================================================

-- ① 卡号归一：去首尾空白、统一大写（对应 service.dupKey 的 SQL 可达部分）
UPDATE t_flog_apply_card
SET bill_no = UPPER(TRIM(bill_no))
WHERE bill_no IS NOT NULL AND bill_no <> UPPER(TRIM(bill_no));

-- ② 冗余字号以底档为准回填（老数据 site_no 与底档不一致/为空的全部纠正）
UPDATE t_flog_apply_card c
JOIN t_flog_stand_doc d ON d.id = c.site_id AND d.del_flag = 0
SET c.site_no = d.site_no
WHERE c.del_flag = 0
  AND (c.site_no IS NULL OR c.site_no <> d.site_no);

-- ③ 待人工处理清单 A：挂在已停用(1)/状态为空/已删底档下的卡
--    （新规矩 status 为空按未启用保守处理，与 service.refUsable 同口径；存量先改挂在册底档）
-- SELECT c.id, c.bill_no, c.site_id, d.status
-- FROM t_flog_apply_card c
-- LEFT JOIN t_flog_stand_doc d ON d.id = c.site_id AND d.del_flag = 0
-- WHERE c.del_flag = 0 AND (d.id IS NULL OR d.status = 1 OR d.status IS NULL);

-- ④ 待人工处理清单 B：同底档下归一后仍重复的卡号（不清零，唯一索引建不上）
-- SELECT site_id, bill_no, COUNT(*) AS cnt, GROUP_CONCAT(id ORDER BY create_time, id) AS dup_ids
-- FROM t_flog_apply_card
-- WHERE del_flag = 0 AND bill_no IS NOT NULL
-- GROUP BY site_id, bill_no HAVING COUNT(*) > 1;
-- 处理口径：保留最早一条（最小 create_time,id），其余 UPDATE del_flag=1 软删。

-- ⑤ 待人工处理清单 C：卡号未挂底档字号（非「字号-」开头）的存量行，与 requireBillBelongsToSite 同口径改号：
-- SELECT c.id, c.bill_no, d.site_no
-- FROM t_flog_apply_card c
-- JOIN t_flog_stand_doc d ON d.id = c.site_id
-- WHERE c.del_flag = 0 AND c.bill_no IS NOT NULL
--   AND c.bill_no <> UPPER(d.site_no)
--   AND c.bill_no NOT LIKE CONCAT(UPPER(d.site_no), '-%');

-- ⑥ 存量档位按底档现行阈值统一重算落库（含起不含止、等于上限算高一档，与 bandLevelOf 一致）
UPDATE t_flog_apply_card c
JOIN t_flog_stand_doc d ON d.id = c.site_id AND d.del_flag = 0
SET c.grade_level = CASE
        WHEN c.qty IS NULL OR c.qty <= 0 THEN 0
        WHEN d.th1_max IS NOT NULL AND c.qty <= d.th1_max THEN 1
        WHEN d.th1_max IS NOT NULL AND d.th2_max IS NOT NULL AND c.qty <= d.th2_max THEN 2
        WHEN d.th2_max IS NOT NULL AND d.th3_max IS NOT NULL AND c.qty <= d.th3_max THEN 3
        ELSE 4
    END,
    c.update_time = NOW(),
    c.update_by = 'migration-recalc'
WHERE c.del_flag = 0;

-- ⑦ 历史「创建人被卡号顶掉」回溯：操作日志按真实登录人记录 oper_name，
--    oper_param 形如 {"billNo":["DC00-2026-001"],...}。
--    每卡取最早一条新增日志的登录人回填 create_by（只回填被污染/为空的，不覆盖已正确的值）。
UPDATE t_flog_apply_card c
JOIN (
    SELECT c2.id AS card_id, MIN(l.create_time) AS first_log_time
    FROM t_flog_apply_card c2
    JOIN t_sys_oper_log l
      ON l.oper_url = '/FlogApplyCardController/add'
     AND l.oper_param LIKE CONCAT('%"billNo":["', c2.bill_no, '"]%')
    WHERE c2.del_flag = 0
    GROUP BY c2.id
) f ON f.card_id = c.id
JOIN t_sys_oper_log l
  ON l.oper_url = '/FlogApplyCardController/add'
 AND l.oper_param LIKE CONCAT('%"billNo":["', c.bill_no, '"]%')
 AND l.create_time = f.first_log_time
SET c.create_by = l.oper_name
WHERE c.del_flag = 0
  AND (c.create_by IS NULL OR UPPER(TRIM(c.create_by)) = c.bill_no);

-- ⑧ 唯一索引（幂等）。必须在 ③④⑤ 清单清零后执行。
DROP PROCEDURE IF EXISTS p_add_uk_apply_card;
DELIMITER $$
CREATE PROCEDURE p_add_uk_apply_card()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE()
                     AND table_name = 't_flog_apply_card'
                     AND index_name = 'uk_apply_card_site_bill_active') THEN
        ALTER TABLE t_flog_apply_card
            ADD UNIQUE KEY uk_apply_card_site_bill_active
              ((CASE WHEN del_flag = 0 THEN site_id END),
               (CASE WHEN del_flag = 0 THEN bill_no END));
    END IF;
END$$
DELIMITER ;
CALL p_add_uk_apply_card();
DROP PROCEDURE IF EXISTS p_add_uk_apply_card;

-- ⑨ 菜单/权限种子：顶级目录「林木采伐管理」(910000) + 建卡菜单(910001) + 六个按钮点
INSERT IGNORE INTO t_sys_permission
    (id, name, descripion, url, is_blank, pid, perms, type, icon, order_num, visible, create_by, create_time)
VALUES
    (910000, '林木采伐管理', NULL, '', 0, 0, '', 0, 'layui-icon-template-1', 90, 0, 'seed', NOW()),
    (910001, '伐区采伐申请建档卡', '伐区采伐申请建档卡菜单', '/FlogApplyCardController/view', 0, 910000, 'flog:flogApplyCard:view', 1, 'layui-icon-read', 1, 0, 'seed', NOW()),
    (910002, '建档卡集合查询', '建档卡集合查询', '/FlogApplyCardController/list', 0, 910001, 'flog:flogApplyCard:list', 2, NULL, 1, 0, 'seed', NOW()),
    (910003, '建档卡新增', '建档卡新增', '/FlogApplyCardController/add', 0, 910001, 'flog:flogApplyCard:add', 2, 'entypo-plus-squared', 2, 0, 'seed', NOW()),
    (910004, '建档卡修改', '建档卡修改', '/FlogApplyCardController/edit', 0, 910001, 'flog:flogApplyCard:edit', 2, 'fa fa-wrench', 3, 0, 'seed', NOW()),
    (910005, '建档卡删除', '建档卡删除', '/FlogApplyCardController/remove', 0, 910001, 'flog:flogApplyCard:remove', 2, 'entypo-trash', 4, 0, 'seed', NOW()),
    (910006, '建档卡重算落库', '建档卡档位重算落库', '/FlogApplyCardController/recalc', 0, 910001, 'flog:flogApplyCard:recalc', 2, 'layui-icon-refresh-3', 5, 0, 'seed', NOW()),
    (910007, '建档卡存量搬入', '建档卡存量数据批量搬入', '/FlogApplyCardController/importData', 0, 910001, 'flog:flogApplyCard:import', 2, 'layui-icon-upload-drag', 6, 0, 'seed', NOW());

-- ⑩ 管理员角色（488243256161730560）授予目录+七个权限点，其他角色按需在权限管理里勾选
INSERT IGNORE INTO t_sys_permission_role
    (id, role_id, permission_id, create_by, create_time)
VALUES
    (911000, 488243256161730560, 910000, 'seed', NOW()),
    (911001, 488243256161730560, 910001, 'seed', NOW()),
    (911002, 488243256161730560, 910002, 'seed', NOW()),
    (911003, 488243256161730560, 910003, 'seed', NOW()),
    (911004, 488243256161730560, 910004, 'seed', NOW()),
    (911005, 488243256161730560, 910005, 'seed', NOW()),
    (911006, 488243256161730560, 910006, 'seed', NOW()),
    (911007, 488243256161730560, 910007, 'seed', NOW());

