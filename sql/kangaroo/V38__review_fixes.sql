-- =============================================================================
-- V38__review_fixes（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
-- =============================================================================
-- @kg-header-begin
-- ---- 迁移头部标准片段（原样复制自 sql/kangaroo/_snippets/header.sql，勿手改）----
-- 用 mysql 客户端跑脚本时会话时区是 SYSTEM；钉到 +08:00，now() 写进审计列才和应用写的对得上
set time_zone = '+08:00';

drop procedure if exists kg_assert_env;
drop procedure if exists kg_assert_same_collation;
drop procedure if exists kg_add_col_if_absent;
drop procedure if exists kg_add_idx_if_absent;
drop procedure if exists kg_drop_col_if_present;
drop procedure if exists kg_drop_idx_if_present;

delimiter $$

-- 库默认字符集必须是 utf8mb4；p_requires 列出的前置迁移版本（逗号分隔）必须都已记在账本里
create procedure kg_assert_env(in p_requires varchar(255))
begin
    declare v_left varchar(255) default trim(ifnull(p_requires, ''));
    declare v_ver varchar(16);
    declare v_pos int;
    declare v_msg varchar(255);
    if @@character_set_database <> 'utf8mb4' then
        signal sqlstate '45000' set message_text = '[kg_assert_env] 库默认字符集不是 utf8mb4，已中止';
    end if;
    if v_left <> '' and not exists (select 1 from information_schema.tables
                                    where table_schema = database() and table_name = 'kg_schema_version') then
        signal sqlstate '45000' set message_text = '[kg_assert_env] 没有迁移账本 kg_schema_version（V6 未执行），已中止';
    end if;
    while v_left <> '' do
        set v_pos = locate(',', v_left);
        if v_pos = 0 then
            set v_ver = v_left;
            set v_left = '';
        else
            set v_ver = trim(substring(v_left, 1, v_pos - 1));
            set v_left = trim(substring(v_left, v_pos + 1));
        end if;
        if v_ver <> '' and not exists (select 1 from kg_schema_version where version = cast(v_ver as unsigned)) then
            set v_msg = concat('[kg_assert_env] 前置迁移 V', v_ver, ' 未执行（账本里没有），已中止');
            signal sqlstate '45000' set message_text = v_msg;
        end if;
    end while;
end$$

-- 新表的排序规则必须与 kg_application 相同，否则 join 时报 Illegal mix of collations
create procedure kg_assert_same_collation(in p_table varchar(64))
begin
    declare v_ref varchar(64);
    declare v_got varchar(64);
    declare v_msg varchar(255);
    set v_ref = (select table_collation from information_schema.tables
                  where table_schema = database() and table_name = 'kg_application');
    set v_got = (select table_collation from information_schema.tables
                  where table_schema = database() and table_name = p_table);
    if v_got is null or v_ref is null or v_got <> v_ref then
        -- SIGNAL 的 message_text 上限 128 个字符，这里控制在 100 以内
        set v_msg = left(concat('[kg_assert_same_collation] ', p_table, ' 排序规则 ', ifnull(v_got, '(无此表)'),
                                ' ≠ kg_application 的 ', ifnull(v_ref, '(无此表)')), 120);
        signal sqlstate '45000' set message_text = v_msg;
    end if;
end$$

create procedure kg_add_col_if_absent(in p_table varchar(64), in p_column varchar(64), in p_ddl varchar(1024))
begin
    if not exists (select 1 from information_schema.columns
                   where table_schema = database() and table_name = p_table and column_name = p_column) then
        set @kg_s = concat('alter table ', p_table, ' add column ', p_ddl);
        prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
    end if;
end$$

create procedure kg_add_idx_if_absent(in p_table varchar(64), in p_index varchar(64), in p_ddl varchar(1024))
begin
    if not exists (select 1 from information_schema.statistics
                   where table_schema = database() and table_name = p_table and index_name = p_index) then
        set @kg_s = concat('alter table ', p_table, ' add ', p_ddl);
        prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
    end if;
end$$

create procedure kg_drop_col_if_present(in p_table varchar(64), in p_column varchar(64))
begin
    if exists (select 1 from information_schema.columns
               where table_schema = database() and table_name = p_table and column_name = p_column) then
        set @kg_s = concat('alter table ', p_table, ' drop column ', p_column);
        prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
    end if;
end$$

create procedure kg_drop_idx_if_present(in p_table varchar(64), in p_index varchar(64))
begin
    if exists (select 1 from information_schema.statistics
               where table_schema = database() and table_name = p_table and index_name = p_index) then
        set @kg_s = concat('alter table ', p_table, ' drop index ', p_index);
        prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
    end if;
end$$

delimiter ;
-- ---- 迁移头部标准片段结束 ----
-- @kg-header-end
call kg_assert_env('37');

-- V38（2026-10-11）：10-11 版本复核后的修复。只加一个索引、收紧小程序公告的后台权限；不改表结构、不改业务数据。
-- 各段开头列出了本段的改动。

-- =============================================================================
-- 小程序公告权限收紧（V38_notice_grants.sql）
-- =============================================================================
-- 小程序公告权限收紧（2026-10-11 复核）
-- 之前（V37）：只要角色能「查看常见问题」（kangaroo:faq:list），就同时拿到小程序公告的查看、新增、修改、删除全部权限，
--   只读的客服角色也能发一条「所有人 + 弹窗」的公告。
-- 现在：公告的新增 / 修改 / 删除分别跟着常见问题的新增 / 修改 / 删除走（faq:add / faq:edit / faq:remove），查看 / 查询保持不变；
--   超级管理员角色 1 保留全部。只删多给的授权，不新增；运营之后在后台「角色管理」里手动给的授权也会按这条规则重新核对一次。
-- 无新表、无新列、无新索引

drop temporary table if exists kg_tmp_faq_grants;
create temporary table kg_tmp_faq_grants as
select distinct rm.role_id, replace(f.perms, 'kangaroo:faq:', 'kangaroo:appnotice:') perms
  from sys_role_menu rm join sys_menu f on f.menu_id = rm.menu_id
 where f.perms in ('kangaroo:faq:add', 'kangaroo:faq:edit', 'kangaroo:faq:remove');

delete rm from sys_role_menu rm
  join sys_menu n on n.menu_id = rm.menu_id
 where n.perms in ('kangaroo:appnotice:add', 'kangaroo:appnotice:edit', 'kangaroo:appnotice:remove')
   and rm.role_id <> 1
   and not exists (select 1 from kg_tmp_faq_grants g where g.role_id = rm.role_id and g.perms = n.perms);

drop temporary table kg_tmp_faq_grants;

-- =============================================================================
-- 积分按人查询补索引（V38_points_fix.sql）
-- =============================================================================
-- 积分按人查询补索引（2026-10-11 复核）
-- 新增索引：kg_point_entry.idx_point_user (app_user_id, id)
-- 无新表、无新列；不改动任何已有数据，可重复执行。
--
-- 之前积分流水只有唯一键 uk_point_biz (biz_type, biz_id, app_user_id)，用户 ID 排在最后用不上：
--   「会员与积分」的可用 / 累计获得 / 已使用、积分明细条数、按流水号倒序翻页，以及成长等级、权益资格算累计获得，
--   每次都要把整张积分流水表扫一遍，流水越多越慢。
-- 现在按「用户 + 流水号」建索引：按人合计只读这个人的几行，明细 order by id desc 翻页也直接走索引。

call kg_add_idx_if_absent('kg_point_entry', 'idx_point_user', 'key idx_point_user (app_user_id, id)');

-- @kg-footer-begin
-- ---- 迁移尾部标准片段（原样复制自 sql/kangaroo/_snippets/header.sql，勿手改）----
drop procedure if exists kg_assert_env;
drop procedure if exists kg_assert_same_collation;
drop procedure if exists kg_add_col_if_absent;
drop procedure if exists kg_add_idx_if_absent;
drop procedure if exists kg_drop_col_if_present;
drop procedure if exists kg_drop_idx_if_present;
-- ---- 迁移尾部标准片段结束 ----
-- @kg-footer-end
-- @kg-ledger（本行起不计入 checksum：checksum = 本行之前全部内容的 sha256）
insert into kg_schema_version (version, name, applied_time, checksum)
values (38, 'review_fixes', now(), '500b776ba11753aab4c6e182045129f5d2b9f7f85ed9358f4e82ae0f2a858e63')
on duplicate key update name = 'review_fixes', applied_time = now(), checksum = '500b776ba11753aab4c6e182045129f5d2b9f7f85ed9358f4e82ae0f2a858e63';
