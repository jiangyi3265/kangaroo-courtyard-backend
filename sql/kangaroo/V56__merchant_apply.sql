-- =============================================================================
-- V56__merchant_apply（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('55');

-- V56（2026-10-18）：「成为商家」招募入口（主人：「更多服务里增加成为商家」）。
--   · 新表 kg_merchant_apply：小程序「我的 → 更多服务 → 成为商家」提交的商家入驻申请（联系人、手机号、城市、店铺类型、店铺名称、简单介绍、想招的人）；
--     后台「袋鼠小院 → 商家入驻申请」可看可跟进（改状态、写跟进备注）。
--   · 后台菜单：袋鼠小院 → 商家入驻申请（kangaroo:merchantapply:list / query / edit），页面 kangaroo/ext/merchantapply/index（通用增删改查页）。
--   · 授权：能看「体验官申请」或「客服工单」的角色（本来就在跟进用户提交的东西）一并能看；超级管理员（role 1）也补上。只补不删。
-- 只新建一张表、加菜单和授权，不改动任何已有表和数据；全部语句可重复执行（表已在不重建，菜单已在就跳过，授权用 insert ignore）。
-- 后端要在本段执行之后再发布：新代码启动时核对账本里有 V56（MerchantApplySchema），没有拒绝启动 —— 否则提交申请时才发现没有表。
-- 老版本程序不受影响：不认这张表，也不会去请求新接口。

-- =============================================================================
-- 一、商家入驻申请表
--   status：new 待联系 / contacted 已联系 / settled 已入驻 / rejected 暂不合作；follow_note 是后台跟进备注（用户看不到）。
--   want_types 存中文、用顿号隔开（「义工、兼职」，选项是 义工 / 兼职 / 全职 / 线上任务），只给后台的人看。
-- =============================================================================
create table if not exists kg_merchant_apply (
  apply_id      bigint       not null auto_increment comment '主键',
  app_user_id   bigint       not null                comment '提交人（kg_app_user.user_id）',
  contact_name  varchar(32)  not null                comment '联系人',
  phone         varchar(20)  not null                comment '手机号（后台跟进用，完整保存）',
  city          varchar(32)  not null                comment '城市',
  store_type    varchar(16)  not null                comment '店铺类型 homestay 民宿 / hotel 酒店 / cafe 咖啡餐饮 / bar 酒吧 / other 其他',
  store_name    varchar(64)  not null                comment '店铺名称',
  intro         varchar(500) not null default ''     comment '简单介绍',
  want_types    varchar(64)  not null default ''     comment '想招的人（中文顿号隔开：义工、兼职、全职、线上任务）',
  status        varchar(16)  not null default 'new'  comment 'new 待联系 / contacted 已联系 / settled 已入驻 / rejected 暂不合作',
  follow_note   varchar(500) not null default ''     comment '后台跟进备注（用户看不到）',
  create_by     varchar(64)  not null default ''     comment '创建者（app:用户ID）',
  create_time   datetime     not null                comment '提交时间',
  update_by     varchar(64)  not null default ''     comment '最后跟进人',
  update_time   datetime     null                    comment '最后跟进时间',
  del_flag      char(1)      not null default '0'    comment '0 正常 / 2 已删除',
  primary key (apply_id),
  key idx_merchant_apply_user (app_user_id, create_time),
  key idx_merchant_apply_status (status, create_time)
) engine=innodb comment='商家入驻申请（小程序「成为商家」）';
call kg_assert_same_collation('kg_merchant_apply');
call kg_add_idx_if_absent('kg_merchant_apply', 'idx_merchant_apply_user', 'key idx_merchant_apply_user (app_user_id, create_time)');
call kg_add_idx_if_absent('kg_merchant_apply', 'idx_merchant_apply_status', 'key idx_merchant_apply_status (status, create_time)');

-- =============================================================================
-- 二、后台菜单：袋鼠小院 → 商家入驻申请（菜单号 3960–3962 被占时让数据库自己编号）
--   上级目录和 V55 同一个找法：先按已有的「门店管理」菜单（kangaroo:store:list）找它的上级，找不到再按根目录 path = 'kangaroo' 找；
--   两样都找不到就报错中止，不默默跳过。菜单已经在了不再找、也不报错。排在「会员店铺权益」（3940）后面。
-- =============================================================================
drop procedure if exists kg_v56_merchant_menu;
delimiter $$
create procedure kg_v56_merchant_menu()
begin
    declare v_parent bigint default null;
    declare v_menu bigint default null;
    if not exists (select 1 from sys_menu where perms = 'kangaroo:merchantapply:list') then
        set v_parent = (select m.parent_id from sys_menu m where m.perms = 'kangaroo:store:list' and m.menu_type = 'C'
                         and m.parent_id is not null and m.parent_id <> 0 order by m.menu_id limit 1);
        if v_parent is null then
            set v_parent = (select m.menu_id from sys_menu m where m.parent_id = 0 and m.path = 'kangaroo' and m.menu_type = 'M' order by m.menu_id limit 1);
        end if;
        if v_parent is null then
            signal sqlstate '45000' set message_text = '[V56] 找不到「袋鼠小院」目录（门店管理 kangaroo:store:list 的上级 / path = kangaroo 的根目录），已中止';
        end if;
        insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
        select case when exists (select 1 from sys_menu x where x.menu_id = 3960) then null else 3960 end,
               '商家入驻申请', v_parent, 3960, 'merchantapply', 'kangaroo/ext/merchantapply/index', '', 'KgExtMerchantApply', 1, 0, 'C', '0', '0', 'kangaroo:merchantapply:list', 'peoples', 'admin', sysdate(),
               '小程序「成为商家」提交的入驻申请：看联系方式、改跟进状态、写跟进备注';
        set v_menu = (select m.menu_id from sys_menu m where m.perms = 'kangaroo:merchantapply:list' order by m.menu_id limit 1);
        insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
        select case when exists (select 1 from sys_menu x where x.menu_id = 3961) then null else 3961 end,
               '申请查询', v_menu, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:merchantapply:query', '#', 'admin', sysdate(), ''
        from dual where not exists (select 1 from sys_menu x where x.perms = 'kangaroo:merchantapply:query');
        insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
        select case when exists (select 1 from sys_menu x where x.menu_id = 3962) then null else 3962 end,
               '申请跟进', v_menu, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:merchantapply:edit', '#', 'admin', sysdate(), '改跟进状态、写跟进备注'
        from dual where not exists (select 1 from sys_menu x where x.perms = 'kangaroo:merchantapply:edit');
    end if;
end$$
delimiter ;
call kg_v56_merchant_menu();
drop procedure kg_v56_merchant_menu;

-- 能看「体验官申请」或「客服工单」的角色一并能看、能跟进；超级管理员（role 1）也补上。只补不删
insert ignore into sys_role_menu (role_id, menu_id)
select distinct rm.role_id, n.menu_id from sys_role_menu rm
join sys_menu f on f.menu_id = rm.menu_id and f.perms in ('kangaroo:guideapply:list', 'kangaroo:ticket:list')
join sys_menu n on n.perms like 'kangaroo:merchantapply:%';
insert ignore into sys_role_menu (role_id, menu_id)
select 1, n.menu_id from sys_menu n where n.perms like 'kangaroo:merchantapply:%';

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
values (56, 'merchant_apply', now(), '31e5af473a0626b0cb87797e2cad26dfde2cadb5f236d661208be77d68abc2eb')
on duplicate key update name = 'merchant_apply', applied_time = now(), checksum = '31e5af473a0626b0cb87797e2cad26dfde2cadb5f236d661208be77d68abc2eb';
