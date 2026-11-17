-- =============================================================================
-- 袋鼠小院 V3 —— 岗位分类特有的展示字段
--
-- 背景：小程序 /pages/jobs/list.vue 对四类岗位渲染不同的信息行，
--       V1 的 kg_job 只覆盖了义工换宿需要的字段，兼职/全职/在线三类的
--       排班时段、培训、试用期、福利、交付日期、预计工时没有落点。
--       这些是纯展示字段，不涉及金额计算与权限，按页面既有文案结构补齐。
--
-- 幂等：使用 add column if not exists（MySQL 8.0.29+ 不支持该语法，
--       因此用存储过程判断 information_schema，兼容 MySQL 8.0.x 全系列）。
-- 回退：V3__rollback.sql
-- =============================================================================

drop procedure if exists kg_add_column_if_absent;
delimiter $$
create procedure kg_add_column_if_absent(
    in p_table varchar(64), in p_column varchar(64), in p_ddl varchar(512))
begin
    if not exists (
        select 1 from information_schema.columns
        where table_schema = database() and table_name = p_table and column_name = p_column
    ) then
        set @s = concat('alter table ', p_table, ' add column ', p_ddl);
        prepare stmt from @s;
        execute stmt;
        deallocate prepare stmt;
    end if;
end$$
delimiter ;

-- 兼职旅行：排班时段（如 10:00-18:00），排班日沿用 V1 的 shift_desc（如 周六-周日）
call kg_add_column_if_absent('kg_job', 'shift_time',
    "shift_time varchar(32) default null comment '排班时段（如 10:00-18:00）' after shift_desc");

-- 全职旅行：培训项目 / 试用期 / 福利
call kg_add_column_if_absent('kg_job', 'train_desc',
    "train_desc varchar(64) default null comment '培训项目名称' after shift_time");
call kg_add_column_if_absent('kg_job', 'probation',
    "probation varchar(32) default null comment '试用期描述' after train_desc");
call kg_add_column_if_absent('kg_job', 'benefits',
    "benefits varchar(255) default null comment '福利，英文逗号分隔' after probation");

-- 在线兼职：交付日期 / 预计工时 / 是否远程
call kg_add_column_if_absent('kg_job', 'deliver_desc',
    "deliver_desc varchar(32) default null comment '交付日期描述（如 2天内）' after benefits");
call kg_add_column_if_absent('kg_job', 'est_hours',
    "est_hours varchar(32) default null comment '预计工时（如 2-3小时）' after deliver_desc");
call kg_add_column_if_absent('kg_job', 'remote_flag',
    "remote_flag char(1) default '1' comment '是否远程岗位（0是 1否）' after est_hours");

-- 在线兼职的 loc 位显示的是发单方名称而非地点，单独一列避免和 address 混淆
call kg_add_column_if_absent('kg_job', 'publisher_name',
    "publisher_name varchar(64) default null comment '发单方名称（在线兼职列表展示用）' after remote_flag");

drop procedure if exists kg_add_column_if_absent;
