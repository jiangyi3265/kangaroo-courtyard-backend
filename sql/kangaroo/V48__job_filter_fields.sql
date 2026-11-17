-- =============================================================================
-- V48__job_filter_fields（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('47');

-- V48（2026-10-16）：岗位筛选条件 —— 性别偏好、休息排班、住宿房型（第二轮改版，小程序岗位列表的筛选面板）。
--   kg_job 新增三列，都是门店选填、不可空、默认 ''（= 没填）：
--     · gender_pref char(1)：'' 男女不限 / F 女 / M 男。只对义工换宿；兼职、全职、线上任务一律 ''（招聘不能写性别限制）；
--     · rest_mode varchar(8)：1-1 做一休一 / 4-3 做四休三 / 2-1 做二休一 / 5-2 做五休二 / 3-2 做三休二 / 3-1 做三休一 / 6-1 做六休一。线上任务一律 ''；
--     · room_type varchar(16)：single 单人间 / double 双人间 / triple 三人间 / multi 多人间。只在包住时有意义（义工换宿，或食宿是包吃包住 / 包住宿）。
--   用户在岗位列表筛某一项时，没填的岗位不算符合（性别例外：没填 = 男女不限）。门店小程序发布页、后台岗位管理都能填；
--   门店发布页同时开始写最短在店天数 min_days（列已存在，不改）。
--   老岗位一次性按已有文字自动填（只在第一次执行、账本里还没有 V48 时；之后运营在后台改过的不会被改回）：
--     · 排班方式 shift_desc 里写了且只写了一种「做X休Y」（做五休二 / 做5休2，空格不算）的线下岗位 → rest_mode；
--     · 食宿说明 board_desc 里写了且只写了一种房型（单人间 / 双人间、两人间 / 三人间 / 多人间、四人间、六人间、八人间）的包住岗位 → room_type。
--   性别偏好不自动填（老岗位一律男女不限）。
-- 可重复执行。后端要在本段执行之后再发布：新代码读写这三列，缺了拒绝启动（JobFilterFieldsSchema）。
-- 老版本程序不受影响：不读不写这三列，新增岗位时靠默认值落成 ''。

-- =============================================================================
-- 一、三列
-- =============================================================================
call kg_add_col_if_absent('kg_job', 'gender_pref', 'gender_pref char(1) not null default '''' comment ''性别偏好（空 男女不限 / F 女 / M 男；只对义工换宿）'' after work_kind');
call kg_add_col_if_absent('kg_job', 'rest_mode', 'rest_mode varchar(8) not null default '''' comment ''休息排班（1-1 做一休一 … 6-1 做六休一；空 = 没填；线上任务为空）'' after shift_desc');
call kg_add_col_if_absent('kg_job', 'room_type', 'room_type varchar(16) not null default '''' comment ''住宿房型（single 单人间 / double 双人间 / triple 三人间 / multi 多人间；空 = 没填；不包住为空）'' after board_desc');

-- =============================================================================
-- 二、老岗位按已有文字自动填（只在第一次执行时）
-- =============================================================================
-- 先把要看的文字（去掉半角、全角空格）抄进临时表，再按字面写法匹配。
-- 写法是字符串常量，排序规则跟着岗位表的列走，不会因为库的默认排序规则不同报 Illegal mix of collations。
drop temporary table if exists kg_v48_text;
create temporary table kg_v48_text as
select j.job_id, j.job_type, j.board_type,
       replace(replace(ifnull(j.shift_desc, ''), ' ', ''), '　', '') shift_txt,
       replace(replace(ifnull(j.board_desc, ''), ' ', ''), '　', '') board_txt
  from kg_job j
 where not exists (select 1 from kg_schema_version v where v.version = 48)
   and (j.shift_desc is not null or j.board_desc is not null);

-- 休息排班：只写了一种的才填（写了「做五休二或做六休一」的不猜）
update kg_job j join kg_v48_text t on t.job_id = j.job_id
   set j.rest_mode = case
         when t.shift_txt like '%做一休一%' or t.shift_txt like '%做1休1%' then '1-1'
         when t.shift_txt like '%做四休三%' or t.shift_txt like '%做4休3%' then '4-3'
         when t.shift_txt like '%做二休一%' or t.shift_txt like '%做2休1%' then '2-1'
         when t.shift_txt like '%做五休二%' or t.shift_txt like '%做5休2%' then '5-2'
         when t.shift_txt like '%做三休二%' or t.shift_txt like '%做3休2%' then '3-2'
         when t.shift_txt like '%做三休一%' or t.shift_txt like '%做3休1%' then '3-1'
         when t.shift_txt like '%做六休一%' or t.shift_txt like '%做6休1%' then '6-1'
       end
 where j.rest_mode = ''
   and t.job_type <> 'online'
   and (t.shift_txt like '%做一休一%' or t.shift_txt like '%做1休1%')
     + (t.shift_txt like '%做四休三%' or t.shift_txt like '%做4休3%')
     + (t.shift_txt like '%做二休一%' or t.shift_txt like '%做2休1%')
     + (t.shift_txt like '%做五休二%' or t.shift_txt like '%做5休2%')
     + (t.shift_txt like '%做三休二%' or t.shift_txt like '%做3休2%')
     + (t.shift_txt like '%做三休一%' or t.shift_txt like '%做3休1%')
     + (t.shift_txt like '%做六休一%' or t.shift_txt like '%做6休1%') = 1;

-- 住宿房型：只看食宿说明（写的是给员工住的房间；工作内容里的「单人间」多半说的是客房，不看），只写了一种的才填
update kg_job j join kg_v48_text t on t.job_id = j.job_id
   set j.room_type = case
         when t.board_txt like '%单人间%' then 'single'
         when t.board_txt like '%双人间%' or t.board_txt like '%两人间%' then 'double'
         when t.board_txt like '%三人间%' then 'triple'
         when t.board_txt like '%多人间%' or t.board_txt like '%四人间%' or t.board_txt like '%六人间%' or t.board_txt like '%八人间%' then 'multi'
       end
 where j.room_type = ''
   and t.job_type <> 'online'
   and (t.job_type = 'volunteer' or t.board_type in ('all', 'room'))
   and (t.board_txt like '%单人间%')
     + (t.board_txt like '%双人间%' or t.board_txt like '%两人间%')
     + (t.board_txt like '%三人间%')
     + (t.board_txt like '%多人间%' or t.board_txt like '%四人间%' or t.board_txt like '%六人间%' or t.board_txt like '%八人间%') = 1;

drop temporary table kg_v48_text;

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
values (48, 'job_filter_fields', now(), '53c647fca3a7500e1c8f743d71aefd5cffc2ae88972e23be5363013038574a95')
on duplicate key update name = 'job_filter_fields', applied_time = now(), checksum = '53c647fca3a7500e1c8f743d71aefd5cffc2ae88972e23be5363013038574a95';
