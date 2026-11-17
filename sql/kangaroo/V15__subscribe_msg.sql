-- =============================================================================
-- V15__subscribe_msg（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('14');

-- V15 微信订阅消息：模板配置、用户授权额度、发送留痕。
-- 约定同 V7–V14。没有配置模板、或 kangaroo.subscribe.mode=none 时，一条订阅消息都不会发，站内信照常写。

create table if not exists kg_subscribe_template (
  tpl_id        bigint       not null auto_increment comment 'ID',
  biz_key       varchar(32)  not null                comment '业务类型，与 kg_notify_pref.pref_key 一致（job_new/apply_progress/activity/order/review/community）',
  title         varchar(64)  not null                comment '后台展示名',
  template_id   varchar(64)  not null                comment '微信订阅消息模板ID（公众平台申请后填入）',
  page          varchar(200) null                    comment '点击后打开的小程序页面；留空用消息自带的跳转路由',
  fields        varchar(500) not null default ''     comment '字段映射，如 thing1=title,thing2=content,time3=now；留空只发标题',
  status        char(1)      not null default '0'    comment '0 启用 / 1 停用',
  remark        varchar(200) null                    comment '备注',
  create_by     varchar(64)  null,
  create_time   datetime     null,
  update_by     varchar(64)  null,
  update_time   datetime     null,
  primary key (tpl_id),
  unique key uk_subscribe_biz (biz_key)
) engine=innodb comment='微信订阅消息模板配置（模板ID由微信公众平台申请，后台填入）';
call kg_assert_same_collation('kg_subscribe_template');

create table if not exists kg_subscribe_grant (
  id            bigint       not null auto_increment comment 'ID',
  app_user_id   bigint       not null                comment '用户',
  template_id   varchar(64)  not null                comment '微信模板ID',
  quota         int          not null default 0      comment '剩余可发送次数（一次性订阅：用户每授权一次 +1，发送一次 -1）',
  accepted_time datetime     null                    comment '最近一次授权时间',
  update_time   datetime     null,
  primary key (id),
  unique key uk_subscribe_grant (app_user_id, template_id)
) engine=innodb comment='用户对订阅消息模板的授权额度（微信一次性订阅，发一条扣一次）';
call kg_assert_same_collation('kg_subscribe_grant');

create table if not exists kg_subscribe_log (
  log_id        bigint       not null auto_increment comment 'ID',
  app_user_id   bigint       not null                comment '接收人',
  biz_key       varchar(32)  not null                comment '业务类型',
  template_id   varchar(64)  not null default ''     comment '微信模板ID',
  msg_id        bigint       null                    comment '对应的站内信ID',
  status        varchar(16)  not null                comment 'sent 已发送 / skipped 未发送 / failed 失败',
  detail        varchar(255) not null default ''     comment '未发送或失败的原因',
  create_time   datetime     null,
  primary key (log_id),
  key idx_subscribe_log_user (app_user_id, create_time)
) engine=innodb comment='订阅消息发送留痕（发没发、为什么没发，都记下来）';
call kg_assert_same_collation('kg_subscribe_log');

-- ---------------------------------------------------------------- 后台菜单 2670–2689
delete from sys_role_menu where menu_id between 2670 and 2689;
delete from sys_menu where menu_id between 2670 and 2689;
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark) values
(2670, '订阅消息模板', 2400, 9, 'substpl', 'kangaroo/ext/substpl/index', '', 'KgExtSubsTpl', 1, 0, 'C', '0', '0', 'kangaroo:substpl:list', 'message', 'admin', sysdate(), ''),
(2671, '模板查询', 2670, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:substpl:query', '#', 'admin', sysdate(), ''),
(2672, '模板新增', 2670, 2, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:substpl:add', '#', 'admin', sysdate(), ''),
(2673, '模板修改', 2670, 3, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:substpl:edit', '#', 'admin', sysdate(), ''),
(2674, '模板删除', 2670, 4, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:substpl:remove', '#', 'admin', sysdate(), ''),
(2680, '订阅发送记录', 2400, 10, 'sublog', 'kangaroo/ext/sublog/index', '', 'KgExtSubLog', 1, 0, 'C', '0', '0', 'kangaroo:sublog:list', 'log', 'admin', sysdate(), ''),
(2681, '发送记录查询', 2680, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:sublog:query', '#', 'admin', sysdate(), '');

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
values (15, 'subscribe_msg', now(), '0ab206ce43fc8f7f3b8378925738541ecb4aa23abf3e4867c90e0452f5aa827c')
on duplicate key update name = 'subscribe_msg', applied_time = now(), checksum = '0ab206ce43fc8f7f3b8378925738541ecb4aa23abf3e4867c90e0452f5aa827c';
