-- =============================================================================
-- V27__city_alias_normalize（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('26');

-- 城市别名归一。门店、房源、活动的城市在后台是自由填写的文本：生产上门店写成了"平潭岛"（字典名是"平潭"，
-- "平潭岛"只是它的搜索别名），用户选"平潭"时这家店的房源和活动按 city = '平潭' 一条都查不到，
-- 订房页的城市列表里还会多出一个"平潭岛"。之后的写入由后端保存时归一（KgCityService.businessCity），这里只处理存量。
-- 只改两类值：
--   ① 恰好属于一个城市 / 目的地的全称或搜索别名、本身又不是字典名（平潭岛 → 平潭、厦门市 → 厦门）；
--   ② V25 补进来的区县名，归到所属城市（思明区 → 厦门，与 businessCity 同口径）。
-- 改之前把原值记进 kg_city_fix_v27，回滚按它逐行还原；同城已有同名门店的不改（uk_store_name_city 会撞）。

create table if not exists kg_city_fix_v27 (
    tbl      varchar(16) not null comment '表名',
    row_id   bigint      not null comment '行主键',
    old_city varchar(64) not null comment '原城市值',
    new_city varchar(64) not null comment '归一后的城市值',
    primary key (tbl, row_id)
) engine = innodb comment = 'V27 城市别名归一前的原值（V27 回滚用）';
call kg_assert_same_collation('kg_city_fix_v27');

drop table if exists kg_city_alias_v27;
create table kg_city_alias_v27 (
    alias     varchar(64) not null primary key,
    canonical varchar(64) not null
) engine = innodb comment = 'V27 执行期间的别名对照，执行完删除';
call kg_assert_same_collation('kg_city_alias_v27');

insert into kg_city_alias_v27 (alias, canonical)
select v.city, min(c.name)
  from (select city from kg_store union select city from kg_stay union select city from kg_activity) v
  join kg_city c on c.level <> 'district' and (c.full_name = v.city or find_in_set(v.city, c.search_aliases) > 0)
 where v.city is not null and v.city <> ''
   and not exists (select 1 from kg_city n where n.name = v.city)
 group by v.city
having count(distinct c.name) = 1;

insert ignore into kg_city_alias_v27 (alias, canonical)
select d.name, min(p.name)
  from kg_city d
  join kg_city p on p.level <> 'district' and p.full_name = d.parent_name
 where d.level = 'district' and d.create_by = 'V25'
   and d.name in (select city from kg_store union select city from kg_stay union select city from kg_activity)
 group by d.name
having count(distinct p.name) = 1;

insert ignore into kg_city_fix_v27 (tbl, row_id, old_city, new_city)
select 'kg_store', s.store_id, s.city, a.canonical
  from kg_store s join kg_city_alias_v27 a on a.alias = s.city
 where not exists (select 1 from kg_store o where s.del_flag = '0' and o.del_flag = '0' and o.store_id <> s.store_id
                      and o.store_name = s.store_name and o.city = a.canonical);
insert ignore into kg_city_fix_v27 (tbl, row_id, old_city, new_city)
select 'kg_stay', t.stay_id, t.city, a.canonical from kg_stay t join kg_city_alias_v27 a on a.alias = t.city;
insert ignore into kg_city_fix_v27 (tbl, row_id, old_city, new_city)
select 'kg_activity', t.activity_id, t.city, a.canonical from kg_activity t join kg_city_alias_v27 a on a.alias = t.city;

-- 重跑时 city 已经是新值，不会再匹配 old_city，等于什么也不做
update kg_store s join kg_city_fix_v27 f on f.tbl = 'kg_store' and f.row_id = s.store_id and s.city = f.old_city set s.city = f.new_city;
update kg_stay t join kg_city_fix_v27 f on f.tbl = 'kg_stay' and f.row_id = t.stay_id and t.city = f.old_city set t.city = f.new_city;
update kg_activity t join kg_city_fix_v27 f on f.tbl = 'kg_activity' and f.row_id = t.activity_id and t.city = f.old_city set t.city = f.new_city;

drop table kg_city_alias_v27;

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
values (27, 'city_alias_normalize', now(), '1c0f0dc2142020c673044bd3e1a06eb79ce5a2e42f39a5aae64888dc64d55e7b')
on duplicate key update name = 'city_alias_normalize', applied_time = now(), checksum = '1c0f0dc2142020c673044bd3e1a06eb79ce5a2e42f39a5aae64888dc64d55e7b';
