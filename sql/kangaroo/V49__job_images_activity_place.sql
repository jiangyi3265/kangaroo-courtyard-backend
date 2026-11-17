-- =============================================================================
-- V49__job_images_activity_place（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('48');

-- V49（2026-10-16）：内测反馈 2.0 —— 岗位照片图集、活动集合地点坐标。
--   · kg_job.images varchar(2000) not null default ''：岗位照片，JSON 数组，只放本平台上传接口给的 /profile/upload/… 路径，最多 9 张。
--     门店在小程序发布 / 换照片、后台岗位管理上传时写入；'' = 没传（老岗位一律 ''，不回填）。
--     岗位列表、卡片、详情的封面取第一张，没有照片时用岗位原来的封面（发布时抄的门店封面）、再没有用门店封面；
--     详情页顶部按这一列做可以左右滑的图集。保存照片时 cover 跟着改成第一张，首页匹配、我的报名等直接读 cover 的地方也就一致了。
--   · kg_activity.meet_lat / meet_lng decimal(9,6) null：活动集合地点在地图上选的点（GCJ-02，即微信 / 腾讯地图坐标）。
--     员工发活动、用户发起活动时选填，要么两个都有、要么都没有；活动详情「集合地点」有坐标时打开地图，没有时复制地址。
-- 可重复执行（列已在就跳过）。后端要在本段执行之后再发布：新代码读写这三列，缺了拒绝启动（JobImagesActivityPlaceSchema）。
-- 老版本程序不受影响：不读不写这三列，新增岗位、活动时靠默认值落成 '' / null。

-- =============================================================================
-- 一、岗位照片图集
-- =============================================================================
call kg_add_col_if_absent('kg_job', 'images', 'images varchar(2000) not null default '''' comment ''岗位照片（JSON 数组，平台上传的 /profile/upload/… 路径，最多 9 张；空 = 没传）'' after cover');

-- =============================================================================
-- 二、活动集合地点坐标
-- =============================================================================
call kg_add_col_if_absent('kg_activity', 'meet_lat', 'meet_lat decimal(9,6) null comment ''集合地点纬度（GCJ-02；和 meet_lng 同时有或同时为空）'' after place');
call kg_add_col_if_absent('kg_activity', 'meet_lng', 'meet_lng decimal(9,6) null comment ''集合地点经度（GCJ-02；和 meet_lat 同时有或同时为空）'' after meet_lat');

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
values (49, 'job_images_activity_place', now(), 'fcc80421d94ead086f97437dc920433125766fcd1a51350bf7e6068fc895155a')
on duplicate key update name = 'job_images_activity_place', applied_time = now(), checksum = 'fcc80421d94ead086f97437dc920433125766fcd1a51350bf7e6068fc895155a';
