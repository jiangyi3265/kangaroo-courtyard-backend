-- =============================================================================
-- 袋鼠小院 V6 —— 迁移账本 kg_schema_version（PLAN WP01；R3 S13）
--
-- 背景：项目里没有 flyway，也没有任何"这个库执行过哪些迁移、执行的是哪一版脚本"的记录。
--       V1 的头部注释就写着"这个库跑过 V1 没有只能靠人记"；开发中改了迁移脚本却没在库里重跑，
--       验收照样全绿，被测的其实是旧结构。
--
-- 做法：
--   1. 建 kg_schema_version(version, name, applied_time, checksum)；
--   2. 回填 V1–V5：先核对它们在本库里的执行痕迹（V1 的 11 张表、V2 的菜单与字典、V3 的列、
--      V4 的三条唯一索引、V5 的菜单隐藏），V1–V4 任一不在就中止 —— 账本不能替没执行过的迁移作证；
--      V5 是可以有意回滚的（开启 Druid 控制台的环境），那种库不回填 V5，一键验收会如实报"缺 V5"；
--   3. 最后一句把 V6 自己记进账本。从 V7 起每个迁移都这样收尾（见 _snippets/header.sql）。
--
-- checksum：脚本文件 CRLF 统一成 LF 后的 sha256 小写十六进制；有"-- @kg-ledger"标记行的脚本只算标记行之前的内容。
--   V1–V5 的 checksum 由本脚本写死回填；改了 V1–V5 中任何一个，要同步改这里的字面量、
--   在库里重跑那个脚本，再重跑本脚本（C17 会核对字面量与脚本是否一致）。
--   一键验收的前置检查逐版比对库里的账本与脚本，不一致判 BLOCKED。
--
-- 幂等：建表 if not exists；回填与自记都是 insert ... on duplicate key update，可重复执行
--       （V1–V5 行重跑时只更新名称与 checksum，保留第一次回填的时间）。
-- 回退：V6__rollback.sql（账本里还有 V7 起的记录时拒绝回滚）。
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
call kg_assert_env('');

-- -----------------------------------------------------------------------------
-- V1–V4 的执行痕迹：任一不在就中止。放在建表之前 —— 中止时库里不留一张空账本
-- -----------------------------------------------------------------------------
drop procedure if exists kg_v6_assert_legacy;

delimiter $$

create procedure kg_v6_assert_legacy()
begin
    declare v_missing varchar(255) default '';
    if (select count(*) from information_schema.tables
         where table_schema = database()
           and table_name in ('kg_app_user', 'kg_store', 'kg_staff_grant', 'kg_job', 'kg_job_favorite',
                              'kg_application', 'kg_application_log', 'kg_interview_slot', 'kg_interview',
                              'kg_message', 'kg_idempotency')) < 11 then
        set v_missing = concat(v_missing, ' V1');
    end if;
    if not exists (select 1 from sys_menu where menu_id = 2000)
       or not exists (select 1 from sys_dict_type where dict_type = 'kg_app_status') then
        set v_missing = concat(v_missing, ' V2');
    end if;
    if (select count(*) from information_schema.columns
         where table_schema = database() and table_name = 'kg_job'
           and column_name in ('shift_time', 'train_desc', 'probation', 'benefits',
                               'deliver_desc', 'est_hours', 'remote_flag', 'publisher_name')) < 8 then
        set v_missing = concat(v_missing, ' V3');
    end if;
    if (select count(distinct table_name, index_name) from information_schema.statistics
         where table_schema = database()
           and ((table_name = 'kg_application' and index_name = 'uk_app_active_user_job')
             or (table_name = 'kg_store' and index_name = 'uk_store_name_city')
             or (table_name = 'kg_interview_slot' and index_name = 'uk_slot_store_range'))) < 3 then
        set v_missing = concat(v_missing, ' V4');
    end if;
    if v_missing <> '' then
        set v_missing = concat('[V6 中止] 本库找不到这些迁移的执行痕迹：', v_missing,
                               '。先按 INTEGRATION.md 第五节补执行，再跑 V6');
        signal sqlstate '45000' set message_text = v_missing;
    end if;
end$$

delimiter ;

call kg_v6_assert_legacy();
drop procedure if exists kg_v6_assert_legacy;

create table if not exists kg_schema_version (
  version       int             not null                   comment '迁移版本号（文件名 V{n}__ 里的 n）',
  name          varchar(64)     not null                   comment '迁移名（文件名 V{n}__ 与 .sql 之间的部分）',
  applied_time  datetime        not null                   comment '执行时间；V1–V5 是 V6 回填它们的时间，不是它们当初的执行时间',
  checksum      char(64)        not null                   comment '脚本 sha256（小写十六进制；换行统一为 LF；有 -- @kg-ledger 标记行时只算标记行之前）',
  primary key (version)
) engine=innodb comment = '迁移账本：本库执行过哪些迁移、执行的是哪一版脚本';

call kg_assert_same_collation('kg_schema_version');

-- -----------------------------------------------------------------------------
-- 回填 V1–V5（checksum 是当前脚本的 sha256；改了脚本要同步这里，C17 会核对）
-- -----------------------------------------------------------------------------
insert into kg_schema_version (version, name, applied_time, checksum)
values (1, 'kangaroo_core', now(), '79d95a76cdcd7981444a6329b197439449c2c97b64b5a1068e4ffed6e3339cec')
on duplicate key update name = 'kangaroo_core', checksum = '79d95a76cdcd7981444a6329b197439449c2c97b64b5a1068e4ffed6e3339cec';

insert into kg_schema_version (version, name, applied_time, checksum)
values (2, 'menu_and_dict', now(), 'b4d549189ff037d21103f2eeb0f48c444ec444b17bb53fa0cd5d08a661030265')
on duplicate key update name = 'menu_and_dict', checksum = 'b4d549189ff037d21103f2eeb0f48c444ec444b17bb53fa0cd5d08a661030265';

insert into kg_schema_version (version, name, applied_time, checksum)
values (3, 'job_display_fields', now(), '8e5862cf9c825ba8fcd75f4ddd05b217658f1c5fd141b5df17a38c22961b118e')
on duplicate key update name = 'job_display_fields', checksum = '8e5862cf9c825ba8fcd75f4ddd05b217658f1c5fd141b5df17a38c22961b118e';

insert into kg_schema_version (version, name, applied_time, checksum)
values (4, 'uniqueness_and_counter_fix', now(), '40a742a4fa35a773fb52b123f3069a084d192a5b9a1d92f734ff0a17538cb1a9')
on duplicate key update name = 'uniqueness_and_counter_fix', checksum = '40a742a4fa35a773fb52b123f3069a084d192a5b9a1d92f734ff0a17538cb1a9';

-- V5 只把「数据监控」菜单设为隐藏；它仍是可见的，说明 V5 没执行或被有意回滚了（开启了 Druid 控制台），
-- 这种库不记 V5。没有这个菜单的库（没有若依系统菜单）V5 本来就是空操作，照常记。
insert into kg_schema_version (version, name, applied_time, checksum)
values (5, 'hide_druid_menu', now(), '5de9ed7c36c98a127c42b9894f44138817396290049ad557016573fd5ccc8cd2')
on duplicate key update name = 'hide_druid_menu', checksum = '5de9ed7c36c98a127c42b9894f44138817396290049ad557016573fd5ccc8cd2';
delete from kg_schema_version
 where version = 5
   and exists (select 1 from sys_menu where menu_id = 111 and path = 'druid' and visible = '0');

select '[V6] 迁移账本已建' as result,
       (select count(*) from kg_schema_version where version between 1 and 5) as 回填的存量迁移数,
       (select group_concat(version order by version) from kg_schema_version where version between 1 and 5) as 回填版本;

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
values (6, 'schema_ledger', now(), 'c4e2017d4aafcf1a0cf3844a27a19943b0ef32f0c1358f1301d3c940d9ca8970')
on duplicate key update name = 'schema_ledger', applied_time = now(), checksum = 'c4e2017d4aafcf1a0cf3844a27a19943b0ef32f0c1358f1301d3c940d9ca8970';
