-- =============================================================================
-- V44__job_month_redpacket（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('43');

-- V44（2026-10-16）：岗位「做满一个月有红包」勾选项 + 注销账号留下的点赞数据一次性修复。
--   一、kg_job 新增 month_redpacket：门店发布岗位时可以勾「做满一个月有红包」，0 = 有、1 = 没有（与 insurance_flag 同一约定），默认 1。
--       小程序岗位列表卡片、岗位详情显示「满月红包」小标签；全部筛选页「更多」里可以只看有满月红包的岗位。
--       老岗位按奖金规则（bonus_desc）里的写法自动勾上：同时写了「红包」和「满一个月 / 满1个月 / 满30天 / 满三十天」（空格不算）。
--       只在第一次执行（账本里还没有 V44）时自动勾；之后运营在后台取消的不会被重新勾上。
--   二、注销账号（kg_app_user.del_flag = '2'）留下的点赞：删掉他们的动态点赞、评论点赞，按点赞表重算动态和评论的点赞数，
--       他们触发的「赞」「评论」通知改成「关联账号已注销」并隐藏（对方资料已清除，消息页不再显示昵称和头像）。
--       之后注销账号时由应用一并清理（KgAccountService），这里只补以前注销留下的。
-- 可重复执行。后端要在本段执行之后再发布：新代码读写 month_redpacket，缺了拒绝启动（MonthRedpacketSchema）。
-- 老版本小程序不受影响：不认识 monthRedpacket 字段就不显示标签。

-- =============================================================================
-- 一、满月红包
-- =============================================================================
call kg_add_col_if_absent('kg_job', 'month_redpacket', 'month_redpacket char(1) not null default ''1'' comment ''做满一个月有红包（0有 1没有）'' after insurance_flag');

-- 老岗位按奖金规则自动勾选（账本里已有 V44 = 之前执行过，不再动，免得把运营手动取消的又勾回来）
update kg_job
   set month_redpacket = '0'
 where month_redpacket = '1'
   and not exists (select 1 from kg_schema_version v where v.version = 44)
   and bonus_desc like '%红包%'
   and (replace(replace(bonus_desc, ' ', ''), '　', '') like '%满一个月%'
        or replace(replace(bonus_desc, ' ', ''), '　', '') like '%满1个月%'
        or replace(replace(bonus_desc, ' ', ''), '　', '') like '%满30天%'
        or replace(replace(bonus_desc, ' ', ''), '　', '') like '%满三十天%');

-- =============================================================================
-- 二、注销账号留下的点赞（一次性修复）
-- =============================================================================
-- 之前注销账号只清了个人资料，点赞记录还在：动态、评论的点赞数里一直算着他，「赞」通知里还挂着他。
delete l from kg_post_like l join kg_app_user u on u.user_id = l.app_user_id and u.del_flag = '2';
delete l from kg_post_comment_like l join kg_app_user u on u.user_id = l.app_user_id and u.del_flag = '2';

-- 点赞数按点赞表重算（子查询读的是点赞表，不是被更新的表，不会触发 MySQL 1093）；只改对不上的行
update kg_post p
   set p.likes = (select count(*) from kg_post_like l where l.post_id = p.post_id)
 where p.likes <> (select count(*) from kg_post_like l2 where l2.post_id = p.post_id);
update kg_post_comment c
   set c.likes = (select count(*) from kg_post_comment_like l where l.comment_id = c.comment_id)
 where c.likes <> (select count(*) from kg_post_comment_like l2 where l2.comment_id = c.comment_id);

-- 注销账号触发的「赞」「评论」通知：去掉触发人和跳转、改成说明文字并隐藏
update kg_message m join kg_app_user u on u.user_id = m.actor_id and u.del_flag = '2'
   set m.title = '关联账号已注销', m.content = '对方的个人资料已清除', m.route = null, m.actor_id = null, m.del_flag = '2'
 where m.category in ('like', 'comment');

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
values (44, 'job_month_redpacket', now(), 'b14ec997d17b42969a7ed1bee9efaa1d843e35e8b738715071efa67271db0304')
on duplicate key update name = 'job_month_redpacket', applied_time = now(), checksum = 'b14ec997d17b42969a7ed1bee9efaa1d843e35e8b738715071efa67271db0304';
