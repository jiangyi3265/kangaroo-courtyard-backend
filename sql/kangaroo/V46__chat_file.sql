-- =============================================================================
-- V46__chat_file（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('45');

-- V46（2026-10-16）：私信放宽 + 门店和报名人之间可以发文件（主人真机反馈）。
--   一、kg_chat_msg.content 由 varchar(1000) 放宽到 varchar(2000)：私信、联系门店、活动群里每条文字最多 2000 字（之前 500 字，程序里限制）。
--       只加长、不改字符集和排序规则（照原列的来），已有消息不动。
--   二、新表 kg_chat_file：门店和报名人 / 接单人的会话里发的文件（zip / rar / 7z、PDF、Word、Excel、PPT，单个不超过 10MB，一条消息一个文件）。
--       文件本身放在服务器私有目录 kg-private/chat/{会话ID}/ 下（不在公开的 /profile/ 里），只有会话里的人能下载，
--       或用本人复制的 3 天内有效的签名链接下载（链接认人：此人不在会话里了，链接也就失效）。用户之间的私信、活动群不能发文件。
-- 可重复执行；老版本程序不受影响（不读新表，列加长不影响读写）。
-- 后端要在本段执行之后再发布：新代码写 2000 字消息、读写新表，缺了拒绝启动（ChatFileSchema）。

-- =============================================================================
-- 一、私信文字 2000 字
-- =============================================================================
-- 之前：私信每条最多 500 字，长一点的任务说明、交付要求要拆成好几条发。
-- 现在：每条最多 2000 字。按原列的字符集、排序规则、可空和注释改长度；已经够长（重复执行）时不动。
-- varchar(1000) → varchar(2000) 在 utf8mb4 下长度前缀都是 2 字节，MySQL 只改表定义、不重建表。
drop procedure if exists kg_v46_widen_chat_content;
delimiter $$
create procedure kg_v46_widen_chat_content()
begin
    declare v_len bigint;
    declare v_cs varchar(64);
    declare v_coll varchar(64);
    select character_maximum_length, character_set_name, collation_name into v_len, v_cs, v_coll
      from information_schema.columns
     where table_schema = database() and table_name = 'kg_chat_msg' and column_name = 'content';
    if v_len is null then
        signal sqlstate '45000' set message_text = 'V46: kg_chat_msg.content not found';
    end if;
    if v_len < 2000 then
        set @kg_s = concat('alter table kg_chat_msg modify column content varchar(2000) character set ', v_cs, ' collate ', v_coll,
                           ' not null comment ''内容（文字最多 2000 字；图片、文件消息存「[图片]」「[文件] 文件名」）''');
        prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
    end if;
end$$
delimiter ;
call kg_v46_widen_chat_content();
drop procedure kg_v46_widen_chat_content;

-- =============================================================================
-- 二、门店和报名人之间发文件
-- =============================================================================
-- 之前：联系门店的会话里只能发文字和图片，报名材料、作品集、合同、任务素材只能另找网盘。
-- 现在：门店会话里可以发一个文件（10MB 以内）；对方在聊天里点文件卡片打开文档、转发到微信聊天、保存到电脑或复制下载链接。
-- 文件和消息同一个事务写入；事务没成功时磁盘上的文件随之删除。服务器从不解压这些文件。
create table if not exists kg_chat_file (
  msg_id       bigint       not null                comment '消息（kg_chat_msg.msg_id），一条消息一个文件',
  conv_id      bigint       not null                comment '会话（kg_chat_conv.conv_id，只有门店会话）',
  sender_id    bigint       not null                comment '发送人（kg_app_user.user_id）',
  file_key     varchar(96)  not null                comment 'kg-private 下的相对路径 chat/{conv_id}/{32位hex}.{扩展名}',
  file_name    varchar(120) not null                comment '原文件名（只用于显示和下载时命名）',
  file_ext     varchar(8)   not null                comment '扩展名（小写）',
  file_size    bigint       not null                comment '字节数',
  input_sha    char(64)     not null                comment '文件内容的 sha256（同一条消息重发时核对是不是同一个文件）',
  create_time  datetime     not null                comment '发送时间',
  primary key (msg_id),
  unique key uk_chat_file_key (file_key),
  key idx_chat_file_sender (sender_id, create_time)
) engine=innodb comment='私信文件（门店和报名人之间，私有存储）';
call kg_assert_same_collation('kg_chat_file');

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
values (46, 'chat_file', now(), '97d72795571bf4cfc5eab6062e5dbb77b02e64267ab71c6f21d27020f7168c9f')
on duplicate key update name = 'chat_file', applied_time = now(), checksum = '97d72795571bf4cfc5eab6062e5dbb77b02e64267ab71c6f21d27020f7168c9f';
