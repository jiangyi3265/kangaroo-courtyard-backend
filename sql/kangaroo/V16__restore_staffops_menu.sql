-- =============================================================================
-- V16__restore_staffops_menu（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('15');

-- ---------------------------------------------------------------- 恢复 V8「员工运营」后台菜单
-- 缺陷：V8 与 V12 都占用了 sys_menu 2500–2599，V12 开头 `delete from sys_menu where menu_id between 2500 and 2599`
-- 把 V8 的员工运营目录（保险方案 / 投保申请 / 调度日志 / 分享追踪 / 员工操作日志）连同角色授权一并删掉了，
-- 后台再也进不去「投保申请 → 登记保单号」，非超管角色也授不到 kangaroo:insapply:handle。
-- 做法：原样把 V8 的菜单换到不再冲突的 2700–2759 号段；perms、组件路径、路由名与 V8 完全一致，
-- 前端 views/kangaroo/ext/{insplan,insapply,dispatch,jobshare,staffop} 与后端 ExtEntity 都不用改。
-- V8 当时的角色授权已被 V12 删掉、无从恢复：受限角色要由管理员在「角色管理」里重新勾选（见 V16 说明）。
delete from sys_role_menu where menu_id between 2700 and 2759;
delete from sys_menu where menu_id between 2700 and 2759;
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark) values
(2700, '员工运营', 0, 13, 'kgstaff', null, '', '', 1, 0, 'M', '0', '0', '', 'peoples', 'admin', sysdate(), '保险、调度等门店员工侧业务（V16 从 V8 的 2500 号段迁来）'),
(2710, '保险方案', 2700, 1, 'insplan',  'kangaroo/ext/insplan/index',  '', 'KgExtInsPlan',  1, 0, 'C', '0', '0', 'kangaroo:insplan:list',  'list',    'admin', sysdate(), ''),
(2711, '方案查询', 2710, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:insplan:query',  '#', 'admin', sysdate(), ''),
(2712, '方案新增', 2710, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:insplan:add',    '#', 'admin', sysdate(), ''),
(2713, '方案修改', 2710, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:insplan:edit',   '#', 'admin', sysdate(), ''),
(2714, '方案删除', 2710, 4, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:insplan:remove', '#', 'admin', sysdate(), ''),
(2720, '投保申请', 2700, 2, 'insapply', 'kangaroo/ext/insapply/index', '', 'KgExtInsApply', 1, 0, 'C', '0', '0', 'kangaroo:insapply:list', 'form',    'admin', sysdate(), ''),
(2721, '投保查询', 2720, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:insapply:query',  '#', 'admin', sysdate(), ''),
(2722, '投保处理', 2720, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:insapply:handle', '#', 'admin', sysdate(), ''),
(2730, '调度日志', 2700, 3, 'dispatch', 'kangaroo/ext/dispatch/index', '', 'KgExtDispatch', 1, 0, 'C', '0', '0', 'kangaroo:dispatch:list', 'log',     'admin', sysdate(), ''),
(2731, '调度查询', 2730, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:dispatch:query',  '#', 'admin', sysdate(), ''),
(2740, '分享追踪', 2700, 4, 'jobshare', 'kangaroo/ext/jobshare/index', '', 'KgExtJobShare', 1, 0, 'C', '0', '0', 'kangaroo:jobshare:list', 'link',    'admin', sysdate(), ''),
(2741, '分享查询', 2740, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:jobshare:query',  '#', 'admin', sysdate(), ''),
(2750, '员工操作日志', 2700, 5, 'staffop', 'kangaroo/ext/staffop/index', '', 'KgExtStaffOp', 1, 0, 'C', '0', '0', 'kangaroo:staffop:list', 'logininfor', 'admin', sysdate(), ''),
(2751, '日志查询', 2750, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:staffop:query',  '#', 'admin', sysdate(), '');

-- ---------------------------------------------------------------- 去掉没有任何实现的菜单权限
-- 这几个按钮权限登记在菜单里，但后端没有对应接口、后台页面（ExtCrud）也没有对应按钮：
-- 勾上它们什么也得不到，却会让运营以为"有导出 / 有会话审核"。等真的实现了再加回来。
delete from sys_role_menu where menu_id in (select menu_id from (select menu_id from sys_menu
  where perms in ('kangaroo:activity:export', 'kangaroo:actsignup:export', 'kangaroo:post:export', 'kangaroo:chatconv:audit')) x);
delete from sys_menu where perms in ('kangaroo:activity:export', 'kangaroo:actsignup:export', 'kangaroo:post:export', 'kangaroo:chatconv:audit');

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
values (16, 'restore_staffops_menu', now(), '2d731fcd0ba76dd89bebeb93e3c481a80f5ba3ea7f03877b75705355a641ee7f')
on duplicate key update name = 'restore_staffops_menu', applied_time = now(), checksum = '2d731fcd0ba76dd89bebeb93e3c481a80f5ba3ea7f03877b75705355a641ee7f';
