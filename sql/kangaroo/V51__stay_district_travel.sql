-- =============================================================================
-- V51__stay_district_travel（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('50');

-- V51（2026-10-17）：旅居改版 R2（主人效果图 6 小院列表、7 小院详情）—— 所在区县、主理人年龄 / 籍贯 / 擅长、出行参考；设施字典补学习室、健身房。
--   主人拍板：不接大模型，「出行参考」由员工在员工端（房源管理 → 编辑）或后台房源管理里手填；其余照旧按数据显示。
--   · kg_stay.district varchar(32)：所在区县（道外区 / 古城区…），员工端从这座城市的区县里选（kg_city level = 'district'，名字原样存），
--     没有合适的也能手填；列表页「位置」下拉按它精确筛选、按它计数；'' = 没填（老房源一律 ''，不回填）。
--   · kg_stay.host_age varchar(16)：主理人年龄，自由文字（32岁 / 90后）；host_hometown varchar(32) 籍贯；host_skills varchar(128) 擅长。
--   · kg_stay.travel_pros / travel_cons / travel_airport / travel_rail varchar(255)：出行参考的优点、缺点、机场怎么到、高铁怎么到。
--     详情页只显示填了的几行，四行都空时整张卡不出现。
--   · 字典 kg_stay_facility 补 study 学习室、gym 健身房（排在热水之后）：列表房型面板「配套设施」、详情页设施、员工端设施勾选都读它。
-- 只加列和字典项，不改动任何已有数据；全部语句可重复执行（列已在就跳过，字典项已在就跳过）。
-- 后端要在本段执行之后再发布：新代码读写这八列，缺了拒绝启动（StayDistrictTravelSchema）。
-- 老版本程序不受影响：不读不写这八列，新增房源时靠默认值落成 ''；老版本前端遇到不认识的设施代码直接不显示。

-- =============================================================================
-- 一、所在区县
-- =============================================================================
call kg_add_col_if_absent('kg_stay', 'district', 'district varchar(32) not null default '''' comment ''所在区县（kg_city 区县名原样，或员工手填；空 = 没填）'' after area');
call kg_add_idx_if_absent('kg_stay', 'idx_stay_district', 'key idx_stay_district (city, district)');

-- =============================================================================
-- 二、主理人年龄 / 籍贯 / 擅长
-- =============================================================================
call kg_add_col_if_absent('kg_stay', 'host_age', 'host_age varchar(16) not null default '''' comment ''主理人年龄（自由文字，如 32岁 / 90后；空 = 没填）'' after host_story');
call kg_add_col_if_absent('kg_stay', 'host_hometown', 'host_hometown varchar(32) not null default '''' comment ''主理人籍贯（空 = 没填）'' after host_age');
call kg_add_col_if_absent('kg_stay', 'host_skills', 'host_skills varchar(128) not null default '''' comment ''主理人擅长（空 = 没填）'' after host_hometown');

-- =============================================================================
-- 三、出行参考（员工手填）
-- =============================================================================
call kg_add_col_if_absent('kg_stay', 'travel_pros', 'travel_pros varchar(255) not null default '''' comment ''出行参考：优点（空 = 没填）'' after host_skills');
call kg_add_col_if_absent('kg_stay', 'travel_cons', 'travel_cons varchar(255) not null default '''' comment ''出行参考：缺点（空 = 没填）'' after travel_pros');
call kg_add_col_if_absent('kg_stay', 'travel_airport', 'travel_airport varchar(255) not null default '''' comment ''出行参考：机场怎么到（空 = 没填）'' after travel_cons');
call kg_add_col_if_absent('kg_stay', 'travel_rail', 'travel_rail varchar(255) not null default '''' comment ''出行参考：高铁怎么到（空 = 没填）'' after travel_airport');

-- =============================================================================
-- 四、设施字典：学习室、健身房
-- =============================================================================
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 8, '学习室', 'study', 'kg_stay_facility', '', '', 'N', '0', 'admin', sysdate(), '能安静看书、办公的公共学习室 / 书房'
from dual where not exists (select 1 from sys_dict_data where dict_type = 'kg_stay_facility' and dict_value = 'study');
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 9, '健身房', 'gym', 'kg_stay_facility', '', '', 'N', '0', 'admin', sysdate(), '院内或楼内可用的健身房 / 健身区'
from dual where not exists (select 1 from sys_dict_data where dict_type = 'kg_stay_facility' and dict_value = 'gym');

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
values (51, 'stay_district_travel', now(), '08c8afa036dbae781820dbd49e115d789411390bdf101345b6ad5111f677db93')
on duplicate key update name = 'stay_district_travel', applied_time = now(), checksum = '08c8afa036dbae781820dbd49e115d789411390bdf101345b6ad5111f677db93';
