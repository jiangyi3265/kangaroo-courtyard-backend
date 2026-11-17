-- =============================================================================
-- V55__dashboard_menu（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('54');

-- V55（2026-10-18）：管理后台「数据看板」（主人：「后台管理端，需要一个展示数据的模块，先展示总的数据，当日的成交金额，入住房间，
--   还有那些房间未入住在往下筛选，可以显示每个主理人的某个地区」）。
--   · 后台菜单：袋鼠小院 → 数据看板（排第一个），页面 kangaroo/dashboard/index，权限 kangaroo:dashboard:view；
--     同一个权限也决定首页要不要显示看板。接口 /kangaroo/dashboard/overview、/kangaroo/dashboard/stay/{stayId} 只读，口径见 KgDashboardService。
--   · 授权：能看「支付单」的角色（本来就看得到全平台的钱）一并能看数据看板；超级管理员（role 1）也补上。只补不删。
-- 只加菜单和授权，不建表、不加列、不改动任何已有数据；全部语句可重复执行（菜单已在就跳过，授权用 insert ignore）。
-- 后端要在本段执行之后再发布：新代码启动时核对账本里有 V55（DashboardMenuSchema），没有拒绝启动 —— 否则菜单缺了谁都进不去看板。
-- 老版本程序不受影响：不认这个菜单的页面组件也不会去请求它（菜单指向的页面是新前端才有的）。

-- =============================================================================
-- 后台菜单：袋鼠小院 → 数据看板（菜单号 2001 被占时让数据库自己编号）
--   上级目录按已有的「门店管理」菜单（kangaroo:store:list）找它的上级（和 V53 按已有子菜单找上级同一个办法）；
--   找不到再退一步按根目录 path = 'kangaroo' 找；两样都找不到就报错中止，不默默跳过。菜单已经在了不再找、也不报错。
-- =============================================================================
drop procedure if exists kg_v55_dashboard_menu;
delimiter $$
create procedure kg_v55_dashboard_menu()
begin
    declare v_parent bigint default null;
    if not exists (select 1 from sys_menu where perms = 'kangaroo:dashboard:view') then
        set v_parent = (select m.parent_id from sys_menu m where m.perms = 'kangaroo:store:list' and m.menu_type = 'C'
                         and m.parent_id is not null and m.parent_id <> 0 order by m.menu_id limit 1);
        if v_parent is null then
            set v_parent = (select m.menu_id from sys_menu m where m.parent_id = 0 and m.path = 'kangaroo' and m.menu_type = 'M' order by m.menu_id limit 1);
        end if;
        if v_parent is null then
            signal sqlstate '45000' set message_text = '[V55] 找不到「袋鼠小院」目录（门店管理 kangaroo:store:list 的上级 / path = kangaroo 的根目录），已中止';
        end if;
        insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
        select case when exists (select 1 from sys_menu x where x.menu_id = 2001) then null else 2001 end,
               '数据看板', v_parent, 0, 'dashboard', 'kangaroo/dashboard/index', '', 'KgDashboard', 1, 0, 'C', '0', '0', 'kangaroo:dashboard:view', 'chart', 'admin', sysdate(),
               '总数据、当日成交与入住、按城市 / 区县 / 主理人 / 小院筛选';
    end if;
end$$
delimiter ;
call kg_v55_dashboard_menu();
drop procedure kg_v55_dashboard_menu;

-- 能看「支付单」的角色一并能看数据看板；超级管理员（role 1）也补上。只补不删
insert ignore into sys_role_menu (role_id, menu_id)
select distinct rm.role_id, n.menu_id from sys_role_menu rm
join sys_menu f on f.menu_id = rm.menu_id and f.perms = 'kangaroo:payorder:list'
join sys_menu n on n.perms = 'kangaroo:dashboard:view';
insert ignore into sys_role_menu (role_id, menu_id)
select 1, n.menu_id from sys_menu n where n.perms = 'kangaroo:dashboard:view';

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
values (55, 'dashboard_menu', now(), '0826a438ebea54d81c102143b3324fe72e68dabcdd72ead638e12aabbf6f1c83')
on duplicate key update name = 'dashboard_menu', applied_time = now(), checksum = '0826a438ebea54d81c102143b3324fe72e68dabcdd72ead638e12aabbf6f1c83';
