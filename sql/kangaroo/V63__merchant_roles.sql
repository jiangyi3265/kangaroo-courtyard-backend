-- =============================================================================
-- V63__merchant_roles（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('60,62');

-- V63：商家端 + 商家服务人 / 城市负责人 + 义工主理人日报周报（2026-10-30）。
-- 依赖写 '60,62'：生产账本没有 V61，这里不依赖 kg_store.images。
-- 老数据不回填：已有的草稿岗位没有需求行，照旧走后台上架。

create table if not exists kg_merchant_request (
  request_id        bigint        not null auto_increment comment '需求ID',
  request_type      varchar(16)   not null                comment 'volunteer 义工 / parttime 兼职 / fulltime 全职 / online_task 线上任务 / managed 代运营 / cloud_chain 云连锁',
  store_id          bigint        null                    comment '商家门店；义工主理人代填且只写了店名时为空',
  store_name        varchar(64)   not null default ''     comment '门店名称快照（后台列表、代填时的店名）',
  merchant_user_id  bigint        null                    comment '提交的商家账号',
  city              varchar(32)   not null default ''     comment '城市（KgCityService.jobCity 归一后）；防串城都按这一列',
  job_id            bigint        null                    comment '对应的草稿岗位 / 线上任务；代运营、云连锁为空',
  title             varchar(64)   not null default ''     comment '标题',
  content           varchar(2000) not null default ''     comment '需求说明',
  contact_name      varchar(32)   not null default ''     comment '联系人',
  contact_phone     varchar(20)   not null default ''     comment '联系电话',
  images            varchar(2000) null                    comment '图片，逗号分隔',
  status            varchar(16)   not null                comment '岗位类：dispatched / published / closed / unassigned；线上类：pending_verify / forwarded / accepted / done / returned / closed / unassigned',
  verifier_user_id  bigint        null                    comment '核实的义工主理人',
  verify_note       varchar(500)  not null default ''     comment '核实备注',
  service_user_id   bigint        null                    comment '商家服务人',
  return_reason     varchar(500)  not null default ''     comment '退回 / 关闭原因',
  source            varchar(16)   not null default 'merchant' comment 'merchant 商家提交 / manager 义工主理人代提交',
  version           int           not null default 0      comment '每次状态变化加一',
  handle_time       datetime      null                    comment '最近一次处理时间',
  del_flag          char(1)       not null default '0'    comment '0 正常 2 删除',
  create_by         varchar(64)   not null default ''     comment '创建者',
  create_time       datetime      not null                comment '创建时间',
  update_by         varchar(64)   not null default ''     comment '更新者',
  update_time       datetime      null                    comment '更新时间',
  primary key (request_id),
  unique key uk_mreq_job (job_id),
  key idx_mreq_city_status (city, status),
  key idx_mreq_store (store_id)
) engine=innodb comment='商家需求（义工/兼职/全职/线上任务/代运营/云连锁）';
call kg_assert_same_collation('kg_merchant_request');

-- 入驻自动通过：记下自动建的门店和授权
call kg_add_col_if_absent('kg_merchant_apply', 'store_id', 'store_id bigint null comment ''自动开通的门店''');
call kg_add_col_if_absent('kg_merchant_apply', 'grant_id', 'grant_id bigint null comment ''自动开通的合作商家授权''');

-- 兼任：一条小院主理人授权上再担任商家服务人。同一人同一店只能一条授权（uk_grant_user_store），所以是开关，不是第二条授权。
-- 必须有默认值：回滚时老 jar 插授权不带这一列；老行都是 0，老账号的权限不变。
call kg_add_col_if_absent('kg_staff_grant', 'also_service', 'also_service char(1) not null default ''0'' comment ''小院主理人兼任商家服务人：1 是 / 0 否（别的角色一律 0）''');

-- 日报两本账：stay 旅居（小院主理人 / 员工）、recruit 招募（义工主理人），互相看不到
call kg_add_col_if_absent('kg_daily_entry', 'ledger', 'ledger varchar(16) not null default ''stay'' comment ''账本：stay 旅居 / recruit 招募''');
call kg_add_idx_if_absent('kg_daily_entry', 'idx_daily_store_ledger_date', 'index idx_daily_store_ledger_date (store_id, ledger, biz_date)');

-- 周报两种：ops 经营周报（原代运营周报）、recruit 义工招募情况周报；同店同周各一份
call kg_add_col_if_absent('kg_weekly_report', 'report_type', 'report_type varchar(16) not null default ''ops'' comment ''ops 经营周报 / recruit 义工招募情况周报''');
call kg_add_idx_if_absent('kg_weekly_report', 'uk_weekly_store_week_type', 'unique key uk_weekly_store_week_type (store_id, week_start, report_type)');
call kg_drop_idx_if_present('kg_weekly_report', 'uk_weekly_store_week');

-- 新角色字典：后台员工授权页的单选来自这里
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 6, '商家服务人', 'merchant_service', 'kg_staff_role', '', 'success', 'N', '0', 'admin', now(), '代运营、云连锁、线上任务派发，按授权门店所在城市接单，仅平台可授权'
where not exists (select 1 from sys_dict_data where dict_type = 'kg_staff_role' and dict_value = 'merchant_service');
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 7, '城市负责人', 'city_lead', 'kg_staff_role', '', 'warning', 'N', '0', 'admin', now(), '收通知，只读看本城需求和各店概况，仅平台可授权'
where not exists (select 1 from sys_dict_data where dict_type = 'kg_staff_role' and dict_value = 'city_lead');

-- 菜单：周报不再只是代运营；新增「商家需求」（放在「商家入驻申请」同一目录，id 3963–3965）
update sys_menu set menu_name = '周报', update_time = now() where perms = 'kangaroo:weeklyreport:list' and menu_name = '代运营周报';

drop procedure if exists kg_v63_merchant_request_menu;
delimiter $$
create procedure kg_v63_merchant_request_menu()
begin
    declare v_parent bigint default null;
    declare v_menu bigint default null;
    set v_parent = (select m.parent_id from sys_menu m where m.perms = 'kangaroo:merchantapply:list' order by m.menu_id limit 1);
    if v_parent is null then
        signal sqlstate '45000' set message_text = '[V63] 找不到「商家入驻申请」菜单的上级目录，已中止';
    end if;
    if not exists (select 1 from sys_menu m where m.perms = 'kangaroo:merchantrequest:list') then
        insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
        select case when exists (select 1 from sys_menu x where x.menu_id = 3963) then null else 3963 end,
               '商家需求', v_parent, 3963, 'merchantrequest', 'kangaroo/ext/merchantrequest/index', '', 'KgExtMerchantRequest', 1, 0, 'C', '0', '0', 'kangaroo:merchantrequest:list', 'message', 'admin', now(),
               '商家发布的招募、线上任务、代运营、云连锁：本城没有人接时等平台处理，可改内容、重新派单';
    end if;
    set v_menu = (select m.menu_id from sys_menu m where m.perms = 'kangaroo:merchantrequest:list' order by m.menu_id limit 1);
    insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
    select case when exists (select 1 from sys_menu x where x.menu_id = 3964) then null else 3964 end,
           '需求查询', v_menu, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:merchantrequest:query', '#', 'admin', now(), ''
    from dual where not exists (select 1 from sys_menu x where x.perms = 'kangaroo:merchantrequest:query');
    insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
    select case when exists (select 1 from sys_menu x where x.menu_id = 3965) then null else 3965 end,
           '需求处理', v_menu, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:merchantrequest:edit', '#', 'admin', now(), '改内容、重新派单'
    from dual where not exists (select 1 from sys_menu x where x.perms = 'kangaroo:merchantrequest:edit');
end$$
delimiter ;
call kg_v63_merchant_request_menu();
drop procedure kg_v63_merchant_request_menu;

-- 能看「商家入驻申请」的后台角色一并能看商家需求；超级管理员（role 1）也补上。只补不删
insert ignore into sys_role_menu (role_id, menu_id)
select distinct rm.role_id, n.menu_id from sys_role_menu rm
join sys_menu f on f.menu_id = rm.menu_id and f.perms = 'kangaroo:merchantapply:list'
join sys_menu n on n.perms like 'kangaroo:merchantrequest:%';
insert ignore into sys_role_menu (role_id, menu_id)
select 1, n.menu_id from sys_menu n where n.perms like 'kangaroo:merchantrequest:%';

-- 派单开关（系统管理 → 参数设置，改完马上生效）：新版小程序覆盖之前，老版本没有核实、完善页面，派给义工主理人也接不住。
-- off = 商家提交的一律进老板队列（后台铃铛、商家需求页），在岗位管理上架，和老流程一样；新版小程序发布后改成 on 才开始派单。
insert into sys_config (config_name, config_key, config_value, config_type, create_by, create_time, remark)
select '商家需求-派给本城员工', 'kangaroo.merchant.staffRouting', 'off', 'Y', 'admin', now(),
       '新版小程序发布后改成 on：商家发布的招募派给本城义工主理人、线上任务给义工主理人核实。off：一律等平台在后台处理。'
from dual where not exists (select 1 from sys_config where config_key = 'kangaroo.merchant.staffRouting');

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
values (63, 'merchant_roles', now(), 'd326dd00ddf1c51dff5319cb2f39c3e80a0d900af5ad423883978e6b01d13f05')
on duplicate key update name = 'merchant_roles', applied_time = now(), checksum = 'd326dd00ddf1c51dff5319cb2f39c3e80a0d900af5ad423883978e6b01d13f05';
