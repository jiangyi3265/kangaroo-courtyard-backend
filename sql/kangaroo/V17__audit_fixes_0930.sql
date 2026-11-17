-- =============================================================================
-- V17__audit_fixes_0930（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('16');

-- V17：2026-09-30 审计修复里需要落到库里的部分（全部可重复执行）
--   1) 评价聚合：kg_stay / kg_store 的评分与评价数此前没有任何代码回写（列注释写着「评价模块回写」），历史评价补算一遍；
--      之后由 KgReviewStats 在评价新增 / 隐藏 / 恢复 / 删除时重算
--   2) 线上任务的计薪方式：员工端曾写入 'fixed'（不在 kg_salary_type 字典和后台白名单里，平台在岗位管理里改不了），
--      统一成字典里有的 'piece'（计件 / 按单）；金额补回两位小数（此前 fen / 100 整数除法丢了角分）
--   3) 字典补全：消息分类 staff（员工待办）；若依自带的 sys_notice_type / sys_notice_status / sys_oper_type
--      （全新安装的 bootstrap 里没有，通知公告页和操作日志页的类型列显示原值、筛选下拉为空）
--   4) sys_menu 自增点：原来停在 2752，落在 V16 保留的 2700–2759 号段里，之后在后台新建的菜单会拿到这个段里的 ID，
--      被 V16 的「按号段删除」误删；推到 3000 以后

-- ---------------------------------------------------------------- 1) 评价聚合补算
update kg_stay s set
  s.review_count = (select count(*) from kg_review r where r.target_type = 'room' and r.target_id = s.stay_id and r.status = '0' and r.del_flag = '0'),
  s.rating = (select round(avg(r.rating), 1) from kg_review r where r.target_type = 'room' and r.target_id = s.stay_id and r.status = '0' and r.del_flag = '0');

update kg_store st set
  st.review_count = (select count(*) from kg_review r where r.store_id = st.store_id and r.status = '0' and r.del_flag = '0'),
  st.rating = (select round(avg(r.rating), 2) from kg_review r where r.store_id = st.store_id and r.status = '0' and r.del_flag = '0');

-- ---------------------------------------------------------------- 2) 线上任务计薪方式
update kg_job j join kg_online_task t on t.job_id = j.job_id
   set j.salary_type = 'piece', j.salary_min = t.commission_fen / 100, j.salary_max = t.commission_fen / 100
 where j.salary_type = 'fixed' and t.commission_fen is not null;
update kg_job set salary_type = 'piece' where salary_type = 'fixed';

-- ---------------------------------------------------------------- 3) 字典补全
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 8, '员工待办', 'staff', 'kg_msg_category', '', 'warning', 'N', '0', 'admin', sysdate(), ''
from dual where not exists (select 1 from sys_dict_data where dict_type = 'kg_msg_category' and dict_value = 'staff');

insert into sys_dict_type (dict_name, dict_type, status, create_by, create_time, remark)
select '通知类型', 'sys_notice_type', '0', 'admin', sysdate(), '通知类型列表'
from dual where not exists (select 1 from sys_dict_type where dict_type = 'sys_notice_type');
insert into sys_dict_type (dict_name, dict_type, status, create_by, create_time, remark)
select '通知状态', 'sys_notice_status', '0', 'admin', sysdate(), '通知状态列表'
from dual where not exists (select 1 from sys_dict_type where dict_type = 'sys_notice_status');
insert into sys_dict_type (dict_name, dict_type, status, create_by, create_time, remark)
select '操作类型', 'sys_oper_type', '0', 'admin', sysdate(), '操作类型列表'
from dual where not exists (select 1 from sys_dict_type where dict_type = 'sys_oper_type');

insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 1, '通知', '1', 'sys_notice_type', '', 'warning', 'Y', '0', 'admin', sysdate(), '通知'
from dual where not exists (select 1 from sys_dict_data where dict_type = 'sys_notice_type' and dict_value = '1');
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 2, '公告', '2', 'sys_notice_type', '', 'success', 'N', '0', 'admin', sysdate(), '公告'
from dual where not exists (select 1 from sys_dict_data where dict_type = 'sys_notice_type' and dict_value = '2');

insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 1, '正常', '0', 'sys_notice_status', '', 'primary', 'Y', '0', 'admin', sysdate(), '正常状态'
from dual where not exists (select 1 from sys_dict_data where dict_type = 'sys_notice_status' and dict_value = '0');
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 2, '关闭', '1', 'sys_notice_status', '', 'danger', 'N', '0', 'admin', sysdate(), '关闭状态'
from dual where not exists (select 1 from sys_dict_data where dict_type = 'sys_notice_status' and dict_value = '1');

insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 99, '其他', '0', 'sys_oper_type', '', 'info', 'N', '0', 'admin', sysdate(), '其他操作'
from dual where not exists (select 1 from sys_dict_data where dict_type = 'sys_oper_type' and dict_value = '0');
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 1, '新增', '1', 'sys_oper_type', '', 'info', 'N', '0', 'admin', sysdate(), '新增操作'
from dual where not exists (select 1 from sys_dict_data where dict_type = 'sys_oper_type' and dict_value = '1');
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 2, '修改', '2', 'sys_oper_type', '', 'info', 'N', '0', 'admin', sysdate(), '修改操作'
from dual where not exists (select 1 from sys_dict_data where dict_type = 'sys_oper_type' and dict_value = '2');
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 3, '删除', '3', 'sys_oper_type', '', 'danger', 'N', '0', 'admin', sysdate(), '删除操作'
from dual where not exists (select 1 from sys_dict_data where dict_type = 'sys_oper_type' and dict_value = '3');
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 4, '授权', '4', 'sys_oper_type', '', 'primary', 'N', '0', 'admin', sysdate(), '授权操作'
from dual where not exists (select 1 from sys_dict_data where dict_type = 'sys_oper_type' and dict_value = '4');
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 5, '导出', '5', 'sys_oper_type', '', 'warning', 'N', '0', 'admin', sysdate(), '导出操作'
from dual where not exists (select 1 from sys_dict_data where dict_type = 'sys_oper_type' and dict_value = '5');
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 6, '导入', '6', 'sys_oper_type', '', 'warning', 'N', '0', 'admin', sysdate(), '导入操作'
from dual where not exists (select 1 from sys_dict_data where dict_type = 'sys_oper_type' and dict_value = '6');
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 7, '强退', '7', 'sys_oper_type', '', 'danger', 'N', '0', 'admin', sysdate(), '强退操作'
from dual where not exists (select 1 from sys_dict_data where dict_type = 'sys_oper_type' and dict_value = '7');
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 8, '生成代码', '8', 'sys_oper_type', '', 'warning', 'N', '0', 'admin', sysdate(), '生成操作'
from dual where not exists (select 1 from sys_dict_data where dict_type = 'sys_oper_type' and dict_value = '8');
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 9, '清空数据', '9', 'sys_oper_type', '', 'danger', 'N', '0', 'admin', sysdate(), '清空操作'
from dual where not exists (select 1 from sys_dict_data where dict_type = 'sys_oper_type' and dict_value = '9');

-- ---------------------------------------------------------------- 4) sys_menu 自增点
set @kg_next_menu = greatest((select coalesce(max(menu_id), 0) + 1 from sys_menu), 3000);
set @kg_alter_sql = concat('alter table sys_menu auto_increment = ', @kg_next_menu);
prepare kg_alter_stmt from @kg_alter_sql;
execute kg_alter_stmt;
deallocate prepare kg_alter_stmt;

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
values (17, 'audit_fixes_0930', now(), '0f89b33c84524c91dd2fd9867b228153f7ed1f111b34ca6d67cb4ceae651db40')
on duplicate key update name = 'audit_fixes_0930', applied_time = now(), checksum = '0f89b33c84524c91dd2fd9867b228153f7ed1f111b34ca6d67cb4ceae651db40';
