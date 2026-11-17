-- =============================================================================
-- 迁移头部/尾部标准片段（00-architecture.md §1.0.1；PLAN WP01）
--
-- 本文件不是迁移，不要单独执行。V6 起的每个迁移：
--   1. 把 [@kg-header-begin, @kg-header-end] 这一段原样复制到文件开头，紧接着
--        call kg_assert_env('<依赖的版本，逗号分隔>');   -- 例如 '6'；V6 自己传 ''
--   2. 建表不写 default charset / collate（继承库默认），建完调用
--        call kg_assert_same_collation('<新表>');
--      加列/加索引/删列/删索引一律用下面四个过程（MySQL 8 没有 add/drop column if [not] exists）；
--   3. 把 [@kg-footer-begin, @kg-footer-end] 这一段原样复制到正文末尾（清掉这些公共过程）；
--   4. 最后一句写账本（前面一行是 "-- @kg-ledger" 标记，标记行起不计入 checksum）：
--        -- @kg-ledger（本行起不计入 checksum）
--        insert into kg_schema_version (version, name, applied_time, checksum)
--        values (N, '<文件名 VN__ 与 .sql 之间的部分>', now(), '<正文 sha256>')
--        on duplicate key update name = '<同上>', applied_time = now(), checksum = '<正文 sha256>';
--      checksum = 标记行之前全部内容（CRLF 统一成 LF）的 sha256 小写十六进制。
--      账本行必须是最后一句：mysql 客户端遇错即停，中途失败的迁移不会被记成"已执行"。
--   回滚脚本末尾删掉自己的账本行：delete from kg_schema_version where version = N;
--
-- C17（e2e/test_migration_guards.py）逐字比对每个 V6+ 迁移里的头部段与尾部段、
-- 核对账本行的 checksum 字面量，并实跑这里的每个过程。改这个文件就要同步改全部 V6+ 迁移。
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

call kg_assert_env('22');

-- 仅补充新周期，不修改已有岗位或运营维护过的字典项。
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 0, '时薪', 'hourly', 'kg_salary_type', '', 'primary', 'N', '0', 'admin', now(), 'salary_periods_v23'
where not exists (select 1 from sys_dict_data where dict_type = 'kg_salary_type' and dict_value = 'hourly');
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 2, '周薪', 'weekly', 'kg_salary_type', '', 'primary', 'N', '0', 'admin', now(), 'salary_periods_v23'
where not exists (select 1 from sys_dict_data where dict_type = 'kg_salary_type' and dict_value = 'weekly');


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
-- @kg-ledger（本行起不计入 checksum）
insert into kg_schema_version (version, name, applied_time, checksum)
values (23, 'salary_periods', now(), 'b7acabc701f5b2b35f19028229ebab02a55d6037cab9df23cc03ae2427d35c70')
on duplicate key update name = 'salary_periods', applied_time = now(), checksum = 'b7acabc701f5b2b35f19028229ebab02a55d6037cab9df23cc03ae2427d35c70';
