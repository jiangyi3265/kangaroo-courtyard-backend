-- =============================================================================
-- V52__post_link（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('51');

-- V52（2026-10-17）：帖子「关联」单独成列（kg_post.link_type varchar(16) not null default '' / link_id bigint null + 索引 idx_post_link）。
--   主人要求：经验分享也能自己选关联的小院 / 门店、岗位或活动（20261017-r1 只有找搭子、旅居攻略能选）。
--   r1 的关联存在 ref_type / ref_id 里（stay / store / job / activity）；经验分享的 ref 已经是「经历校验」那条报名（application），
--   放不下第二个关联，只能自动关联到那条报名的岗位（岗位下架退到门店）。现在关联单独存一列：
--   · link_type：stay 小院 / store 门店 / job 岗位 / activity 活动；'' = 没选（经验分享没选时照旧自动关联经历的岗位）。
--   · link_id：关联对象的编号；没选是 null。
--   · idx_post_link (link_type, link_id)：以后「关联了这个小院 / 岗位的帖子」按它查。
-- 回填：找搭子、旅居攻略已经关联了公开对象的老帖子（ref_type 是 stay / store / job / activity），抄一份到 link_type / link_id；
--   ref_type / ref_id 原样保留（老版本程序还读它）。只抄还没填的（link_type = '' 且 link_id 为空），可重复执行。
--   经验分享（ref 是报名）、关联自己报名 / 住宿订单的老帖子不抄（它们没有公开的关联对象）。
-- 新版本程序：找搭子、旅居攻略照旧写 ref_type / ref_id（老版本程序照样看得到「关联：XXX」），另外同时写 link_type / link_id；
--   经验分享的 ref 照旧是那条报名，自己选了关联才写 link_type / link_id。读的时候先看 link_*，没有再看 ref_*（老版本程序在本段之后发的帖子只写了 ref_*）。
-- 加列不动已有数据；回填只填新列。全部语句可重复执行（列、索引已在就跳过，已回填的不再动）。
-- 后端要在本段执行之后再发布：新代码读写这两列，缺了拒绝启动（PostLinkSchema）。
-- 老版本程序不受影响：不读不写这两列（查询都是 select p.*，多两列不碍事），发帖时靠默认值落成 '' / null。

-- =============================================================================
-- 一、关联列 + 索引
-- =============================================================================
call kg_add_col_if_absent('kg_post', 'link_type', 'link_type varchar(16) not null default '''' comment ''关联对象类型：stay 小院 / store 门店 / job 岗位 / activity 活动；空 = 没选（经验分享自动关联经历的岗位）'' after ref_id');
call kg_add_col_if_absent('kg_post', 'link_id', 'link_id bigint null comment ''关联对象ID（没选为空）'' after link_type');
call kg_add_idx_if_absent('kg_post', 'idx_post_link', 'key idx_post_link (link_type, link_id)');

-- =============================================================================
-- 二、回填：r1 存在 ref_* 里的公开关联抄到 link_*（ref_* 原样保留）
-- =============================================================================
update kg_post set link_type = ref_type, link_id = ref_id
 where link_type = '' and link_id is null and kind <> 'diary'
   and ref_type in ('stay', 'store', 'job', 'activity') and ref_id is not null;

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
values (52, 'post_link', now(), 'c1b3fad22ee2e1d5927c7064ecdfde2075cf939407665e7c731e777f22b927d2')
on duplicate key update name = 'post_link', applied_time = now(), checksum = 'c1b3fad22ee2e1d5927c7064ecdfde2075cf939407665e7c731e777f22b927d2';
