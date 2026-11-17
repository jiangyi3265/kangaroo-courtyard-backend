-- =============================================================================
-- V64__role_switch（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('60,62,63');

-- V64：角色两阶段切换（2026-10-30 主人拍板）。编码不动：manager 就是新义工主理人，city_lead 就是城市负责人，staff 退役。
-- 依赖写 '60,62,63'：生产账本没有 V61，这里不依赖 kg_store.images。
-- 这个迁移只建表、加菜单，不碰 kg_staff_grant 和字典：上线时阶段是 legacy，所有人的权限和今天逐格相同。
-- 切数据是后台「角色切换」页的按钮（KgRoleSwitchService），在一个事务里改授权、字典和阶段。

-- 阶段状态（只有一行，id = 1）。读不到这一行后端拒绝启动（RoleSwitchSchema）。
create table if not exists kg_role_switch (
  id                  tinyint       not null                 comment '固定 1',
  phase               varchar(16)   not null default 'legacy' comment 'legacy 旧规则 / new 新规则',
  fingerprint         char(64)      null                     comment '切换时核对过的名单指纹',
  switched_by         varchar(64)   null                     comment '切换人',
  switched_time       datetime      null                     comment '切换时间（义工主理人生日礼物满 90 天从这天算）',
  rolled_back_by      varchar(64)   null                     comment '最近一次退回的操作人',
  rolled_back_time    datetime      null                     comment '最近一次退回时间',
  rollback_note       varchar(2000) null                     comment '最近一次退回的结果摘要（还原多少行、哪些行没动）',
  routing_opened_by   varchar(64)   null                     comment '打开派单的操作人',
  routing_opened_time datetime      null                     comment '打开派单的时间；有值后不再提供一键退回',
  create_time         datetime      not null                 comment '创建时间',
  update_time         datetime      null                     comment '更新时间',
  primary key (id)
) engine=innodb comment='角色切换阶段';
call kg_assert_same_collation('kg_role_switch');
insert ignore into kg_role_switch (id, phase, create_time) values (1, 'legacy', now());

-- 切换时写入、退回时清空：每行授权的原角色和审计列，以及字典原值。发布脚本和 V64 回滚都要求它是空的。
create table if not exists kg_role_switch_backup (
  backup_id          bigint        not null auto_increment  comment '备份ID',
  kind               varchar(8)    not null                 comment 'grant 授权 / dict 字典',
  grant_id           bigint        null                     comment '授权ID（kind=grant）',
  old_role           varchar(32)   null                     comment '切换前角色',
  new_role           varchar(32)   null                     comment '切换后角色',
  old_update_by      varchar(64)   null                     comment '切换前的 update_by',
  old_update_time    datetime      null                     comment '切换前的 update_time',
  dict_value         varchar(100)  null                     comment '字典值（kind=dict）',
  dict_existed       char(1)       null                     comment '切换前这条字典在不在：1 在 / 0 切换时才补',
  old_dict_label     varchar(100)  null                     comment '切换前文字',
  old_dict_sort      int           null                     comment '切换前排序',
  old_dict_status    char(1)       null                     comment '切换前状态',
  old_dict_default   char(1)       null                     comment '切换前是否默认',
  new_dict_label     varchar(100)  null                     comment '切换后文字',
  new_dict_sort      int           null                     comment '切换后排序',
  new_dict_status    char(1)       null                     comment '切换后状态',
  create_time        datetime      not null                 comment '创建时间',
  primary key (backup_id),
  unique key uk_rsb_grant (grant_id),
  unique key uk_rsb_dict (dict_value)
) engine=innodb comment='角色切换备份（退回时按它还原）';
call kg_assert_same_collation('kg_role_switch_backup');

-- 谁用什么版本：X-App-Client 请求头。只用于切换页显示和「至少见过正式版」这道门槛，不参与授权。
create table if not exists kg_staff_client_seen (
  app_user_id     bigint       not null comment '有员工授权的账号',
  channel         varchar(16)  not null comment '渠道：develop 开发版 / trial 体验版 / release 正式版 / none 没带请求头（老版本）',
  version         varchar(32)  not null default '' comment '小程序版本；老版本不带请求头时为空',
  first_seen_time datetime     not null comment '第一次见到',
  last_seen_time  datetime     not null comment '最近一次见到（内存去重，不是每个请求都写）',
  primary key (app_user_id, channel)
) engine=innodb comment='员工账号的小程序版本';
call kg_assert_same_collation('kg_staff_client_seen');

-- 菜单：放在「员工授权」同一目录，只有超级管理员能看。编号被占时退给自增（线上 3963 已被占，3964–3966 大概率已被 V63 用）。
drop procedure if exists kg_v64_role_switch_menu;
delimiter $$
create procedure kg_v64_role_switch_menu()
begin
    declare v_parent bigint default null;
    declare v_menu bigint default null;
    set v_parent = (select m.parent_id from sys_menu m where m.perms = 'kangaroo:grant:list' order by m.menu_id limit 1);
    if v_parent is null then
        signal sqlstate '45000' set message_text = '[V64] 找不到「员工授权」菜单的上级目录，已中止';
    end if;
    if not exists (select 1 from sys_menu m where m.perms = 'kangaroo:roleswitch:list') then
        insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
        select case when exists (select 1 from sys_menu x where x.menu_id = 3970) then null else 3970 end,
               '角色切换', v_parent, 3970, 'roleswitch', 'kangaroo/roleswitch/index', '', 'KgRoleSwitch', 1, 0, 'C', '0', '0', 'kangaroo:roleswitch:list', 'peoples', 'admin', now(),
               '看名单、切换到新角色、退回、打开派单。只给超级管理员';
    end if;
    set v_menu = (select m.menu_id from sys_menu m where m.perms = 'kangaroo:roleswitch:list' order by m.menu_id limit 1);
    insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
    select case when exists (select 1 from sys_menu x where x.menu_id = 3971) then null else 3971 end,
           '执行切换', v_menu, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:roleswitch:switch', '#', 'admin', now(), ''
    from dual where not exists (select 1 from sys_menu x where x.perms = 'kangaroo:roleswitch:switch');
    insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
    select case when exists (select 1 from sys_menu x where x.menu_id = 3972) then null else 3972 end,
           '退回', v_menu, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:roleswitch:rollback', '#', 'admin', now(), ''
    from dual where not exists (select 1 from sys_menu x where x.perms = 'kangaroo:roleswitch:rollback');
    insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
    select case when exists (select 1 from sys_menu x where x.menu_id = 3973) then null else 3973 end,
           '打开派单', v_menu, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:roleswitch:routing', '#', 'admin', now(), ''
    from dual where not exists (select 1 from sys_menu x where x.perms = 'kangaroo:roleswitch:routing');
end$$
delimiter ;
call kg_v64_role_switch_menu();
drop procedure kg_v64_role_switch_menu;

insert ignore into sys_role_menu (role_id, menu_id)
select 1, n.menu_id from sys_menu n where n.perms like 'kangaroo:roleswitch:%';

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
values (64, 'role_switch', now(), '13c5d2bc13df3a25e0a3bbcf1180159699cc68478ac9aa8a885c09f5965550e0')
on duplicate key update name = 'role_switch', applied_time = now(), checksum = '13c5d2bc13df3a25e0a3bbcf1180159699cc68478ac9aa8a885c09f5965550e0';
