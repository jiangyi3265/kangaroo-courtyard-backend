-- =============================================================================
-- V54__store_profile（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('53');

-- V54（2026-10-18）：岗位详情改版（主人效果图 41「门店页」）—— 门店的采访方式、采访视频、店长介绍、该店亮点、交通参考、营业执照、店里日常。
--   主人拍板：岗位详情只有两种样子 ——「袋鼠实地探访 + 保险」和「袋鼠线上采访」；采访方式由后台「门店」里选：实地探访 / 线上采访 / 未采访。
--   实地探访就是原来的安心商家认证（主人早先说过认证 = 去现场拍过）：选实地探访 = 认证（safe_cert = '0'），选另外两种 = 撤销认证（safe_cert = '1'），
--   所有按 safe_cert 显示的角标、筛选照旧对；原来的认证接口也同步改采访方式（认证 → onsite；撤销且原来是 onsite → ''）。
--   · kg_store.interview_mode varchar(16)：onsite 实地探访 / online 线上采访 / '' 未采访；onsite 与 safe_cert = '0' 同步。
--   · kg_store.interview_video / interview_cover varchar(255)：采访视频（≤5分钟，平台路径 /profile/upload/videos/…）和封面；'' = 没传，详情页不出视频卡。
--   · kg_store.interview_seconds int null：采访视频时长（秒，后台上传时页面量到的）；null = 不知道。
--   · kg_store.interview_note varchar(100)：采访卡说明文字；'' = 用默认的一句。
--   · kg_store.manager_name / manager_avatar / manager_years / manager_skills / manager_quote：店长称呼、头像、在这里工作多久、擅长、店长说。
--   · kg_store.facilities varchar(255)：该店亮点代码（逗号分隔，代码表在 KgStoreProfiles.FACILITIES）。
--   · kg_store.travel_rail / travel_airport varchar(500)、travel_cost varchar(32)：交通参考（高铁 / 机场怎么到）、预估最低出行成本（如 约37元起）。
--   · kg_store.license_image varchar(255)：营业执照照片；daily_images varchar(2000)：店里日常照片（JSON 数组，最多 9 张）。
--   没填的项，岗位详情用这家店小院（kg_stay）的资料兜底：主理人、设施 / 可做饭、出行参考、生活碎片的照片。主人拍板不接大模型，「交通参考」是员工手填的。
-- 回填：已经认证的门店（safe_cert = '0'）采访方式记成 onsite（只填还是 '' 的，可重复执行）；其余列一律 ''（老门店没填）。
-- 加列不改动其他已有数据；全部语句可重复执行（列已在就跳过）。
-- 后端要在本段执行之后再发布：新代码读写这些列，缺了拒绝启动（StoreProfileSchema）。
-- 老版本程序不受影响：门店的查询、新增、修改都是写明列名的，不读不写这些列（新增门店时靠默认值落成 '' / null）；
--   老版本的认证接口只改 safe_cert，采访方式会暂时对不上，新版本程序读的时候以 safe_cert 为准（见 KgStoreProfiles.mode）。

-- =============================================================================
-- 一、采访方式与采访视频
-- =============================================================================
call kg_add_col_if_absent('kg_store', 'interview_mode', 'interview_mode varchar(16) not null default '''' comment ''采访方式（onsite 实地探访 / online 线上采访 / 空 未采访）；onsite 与 safe_cert=0 同步'' after longitude');
call kg_add_col_if_absent('kg_store', 'interview_video', 'interview_video varchar(255) not null default '''' comment ''采访视频（/profile/upload/videos/…，≤5分钟）'' after interview_mode');
call kg_add_col_if_absent('kg_store', 'interview_cover', 'interview_cover varchar(255) not null default '''' comment ''采访视频封面'' after interview_video');
call kg_add_col_if_absent('kg_store', 'interview_seconds', 'interview_seconds int default null comment ''采访视频时长（秒）'' after interview_cover');
call kg_add_col_if_absent('kg_store', 'interview_note', 'interview_note varchar(100) not null default '''' comment ''采访卡说明文字'' after interview_seconds');

-- =============================================================================
-- 二、店长介绍
-- =============================================================================
call kg_add_col_if_absent('kg_store', 'manager_name', 'manager_name varchar(32) not null default '''' comment ''店长称呼'' after interview_note');
call kg_add_col_if_absent('kg_store', 'manager_avatar', 'manager_avatar varchar(255) not null default '''' comment ''店长头像'' after manager_name');
call kg_add_col_if_absent('kg_store', 'manager_years', 'manager_years varchar(32) not null default '''' comment ''在这里工作多久（如 3年）'' after manager_avatar');
call kg_add_col_if_absent('kg_store', 'manager_skills', 'manager_skills varchar(100) not null default '''' comment ''店长擅长'' after manager_years');
call kg_add_col_if_absent('kg_store', 'manager_quote', 'manager_quote varchar(200) not null default '''' comment ''店长说'' after manager_skills');

-- =============================================================================
-- 三、该店亮点、交通参考、营业执照、店里日常
-- =============================================================================
call kg_add_col_if_absent('kg_store', 'facilities', 'facilities varchar(255) not null default '''' comment ''该店亮点代码（逗号分隔）'' after manager_quote');
call kg_add_col_if_absent('kg_store', 'travel_rail', 'travel_rail varchar(500) not null default '''' comment ''交通参考：高铁/火车'' after facilities');
call kg_add_col_if_absent('kg_store', 'travel_airport', 'travel_airport varchar(500) not null default '''' comment ''交通参考：机场'' after travel_rail');
call kg_add_col_if_absent('kg_store', 'travel_cost', 'travel_cost varchar(32) not null default '''' comment ''预估最低出行成本（如 约37元起）'' after travel_airport');
call kg_add_col_if_absent('kg_store', 'license_image', 'license_image varchar(255) not null default '''' comment ''营业执照照片'' after travel_cost');
call kg_add_col_if_absent('kg_store', 'daily_images', 'daily_images varchar(2000) not null default '''' comment ''店里日常照片（JSON 数组，最多 9 张）'' after license_image');

-- =============================================================================
-- 四、回填：已认证的门店 = 实地探访过
-- =============================================================================
update kg_store set interview_mode = 'onsite' where safe_cert = '0' and interview_mode = '';

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
values (54, 'store_profile', now(), '78549c67dde9207eeadfb1eaa1ccf033be9231f47748e7a562afd925a39b097a')
on duplicate key update name = 'store_profile', applied_time = now(), checksum = '78549c67dde9207eeadfb1eaa1ccf033be9231f47748e7a562afd925a39b097a';
