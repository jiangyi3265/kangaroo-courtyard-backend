-- =============================================================================
-- V72__support_chat（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('67');

-- V72：AI袋鼠聊天记录（2026-11-03 主人：「历史聊天记录，永久保存，不能删除」）。
-- 登录用户在 AI袋鼠（底部弹层 components/k-ai-sheet、整页 pages/notice/support）里问的每一句、AI 答的每一句（带信息来源、卡片、跳转按钮）
-- 都追加一行；关掉小程序、换手机、重装后打开还能看到。之前对话只在页面内存里，关掉就没了（kg_support_log 只是匿名统计，答案只留 1000 字摘要）。
-- 只追加：小程序没有删除 / 清空接口，后台「用户服务 → AI客服聊天记录」只读（没有新增、修改、删除按钮和权限）。
-- 唯一的删除口：注销账号时随账号一起清掉（和 kg_support_log 同一口径，个人信息保护法要求）。游客不存。
-- 文字最长 2000 字；卡片和按钮只留展示字段（JSON，最长 4000 字）。查询按 (app_user_id, chat_id) 倒着翻页。
-- 依赖只写 '67'：V68–V71 是别的包，这里不碰它们的表。不依赖角色阶段。
-- 后端要在本段执行之后再发布：新代码启动时核对账本里有 V72、表和列都在（SupportChatSchema），缺了拒绝启动。
-- 退回老 jar 不用回滚这个迁移：老 jar 不认这张表，菜单点进去是空的；聊天记录留在库里，再换回新 jar 照常显示。
-- 全部语句可重复执行（表、索引、菜单已在就跳过，授权用 insert ignore）；不改动已有数据。

create table if not exists kg_support_chat (
  chat_id       bigint        not null auto_increment comment 'ID',
  app_user_id   bigint        not null                comment '用户',
  role          varchar(8)    not null                comment 'user 用户问 / bot AI袋鼠答',
  content       varchar(2000) not null                comment '文字（最长 2000 字）',
  source        varchar(64)   null                    comment '信息来源（回答下面那行灰字）',
  intent        varchar(32)   null                    comment '识别出的意图',
  context       varchar(16)   null                    comment '入口：home / profile / order / city',
  extra         varchar(4000) null                    comment '卡片和跳转按钮（JSON，只留展示字段）',
  create_time   datetime      not null                comment '时间',
  primary key (chat_id),
  key idx_schat_user (app_user_id, chat_id)
) engine=innodb comment='AI袋鼠聊天记录（只追加，用户不能删）';
call kg_assert_same_collation('kg_support_chat');
call kg_add_idx_if_absent('kg_support_chat', 'idx_schat_user', 'key idx_schat_user (app_user_id, chat_id)');

-- 菜单：用户服务 → AI客服聊天记录，紧挨着「客服工单」，只读（只有查询权限，没有新增 / 修改 / 删除）。
-- 上级按「客服工单」（kangaroo:ticket:list）找，找不到再按根目录 path = 'kgusersvc' 找，都找不到报错中止。
-- 优先用固定编号 2425–2426，被占时退给自增（和 V55、V66、V67 同一写法）。
drop procedure if exists kg_v72_supportchat_menu;
delimiter $$
create procedure kg_v72_supportchat_menu()
begin
    declare v_parent bigint default null;
    declare v_order int default 2;
    declare v_menu bigint default null;
    if not exists (select 1 from sys_menu m where m.perms = 'kangaroo:supportchat:list') then
        set v_parent = (select m.parent_id from sys_menu m where m.perms = 'kangaroo:ticket:list' and m.menu_type = 'C'
                         and m.parent_id is not null and m.parent_id <> 0 order by m.menu_id limit 1);
        set v_order = coalesce((select m.order_num from sys_menu m where m.perms = 'kangaroo:ticket:list' and m.menu_type = 'C' order by m.menu_id limit 1), 2);
        if v_parent is null then
            set v_parent = (select m.menu_id from sys_menu m where m.parent_id = 0 and m.path = 'kgusersvc' and m.menu_type = 'M' order by m.menu_id limit 1);
        end if;
        if v_parent is null then
            signal sqlstate '45000' set message_text = '[V72] 找不到「用户服务」目录（客服工单 kangaroo:ticket:list 的上级 / path = kgusersvc 的根目录），已中止';
        end if;
        insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
        select case when exists (select 1 from sys_menu x where x.menu_id = 2425) then null else 2425 end,
               'AI客服聊天记录', v_parent, v_order, 'supportchat', 'kangaroo/ext/supportchat/index', '', 'KgExtSupportChat', 1, 0, 'C', '0', '0', 'kangaroo:supportchat:list', 'message', 'admin', now(),
               '按用户ID查用户和 AI袋鼠的聊天记录，只读。默认给超级管理员和能看客服工单的角色';
    end if;
    set v_menu = (select m.menu_id from sys_menu m where m.perms = 'kangaroo:supportchat:list' order by m.menu_id limit 1);
    insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
    select case when exists (select 1 from sys_menu x where x.menu_id = 2426) then null else 2426 end,
           '记录查询', v_menu, 1, '', null, '', '', 1, 0, 'F', '0', '0', 'kangaroo:supportchat:query', '#', 'admin', now(), ''
    from dual where not exists (select 1 from sys_menu x where x.perms = 'kangaroo:supportchat:query');
end$$
delimiter ;
call kg_v72_supportchat_menu();
drop procedure kg_v72_supportchat_menu;

-- 授权：能看「客服工单」（kangaroo:ticket:list）的角色一并能看聊天记录；超级管理员（role 1）也补上。只补不删
insert ignore into sys_role_menu (role_id, menu_id)
select distinct rm.role_id, n.menu_id from sys_role_menu rm
join sys_menu f on f.menu_id = rm.menu_id and f.perms = 'kangaroo:ticket:list'
join sys_menu n on n.perms like 'kangaroo:supportchat:%';
insert ignore into sys_role_menu (role_id, menu_id)
select 1, n.menu_id from sys_menu n where n.perms like 'kangaroo:supportchat:%';

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
values (72, 'support_chat', now(), '19788339de7a52cd9a9e8cf832b8d5437032b7312ad5a7847fed6c5550f01f7b')
on duplicate key update name = 'support_chat', applied_time = now(), checksum = '19788339de7a52cd9a9e8cf832b8d5437032b7312ad5a7847fed6c5550f01f7b';
