-- =============================================================================
-- V41__online_files（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('40');

-- V41（2026-10-15）：线上任务「发素材」可以带压缩包和文档；「退回修改」可以附截图、把要改的地方写清楚。
--   一、新表 kg_online_material_file：门店发给接单人的素材附件（zip / rar / 7z、PDF、Word、Excel、PPT，单个不超过 10MB）。
--       文件本身放在服务器私有目录 kg-private/omat/{接单ID}/ 下（不在公开的 /profile/ 里），只有本店员工和这位接单人能下载，
--       或用 3 天内有效的签名链接下载。上传了却 24 小时没发出去的，后台每小时清理一次；发出去的一直保留。
--   二、kg_online_submission.review_comment 由 varchar(200) 改成 text：退回说明最多 5000 字（验收通过的评语仍是 200 字，由程序限制）。
--   三、kg_online_submission 新增 review_images：退回修改附的截图（逗号分隔的平台图片路径，最多 9 张）。
-- 不改动任何已有数据，可重复执行。后端要在本段执行之后再发布：新代码读写新表和新列，缺了拒绝启动（OnlineFilesSchema）。

-- =============================================================================
-- 一、线上任务素材附件
-- =============================================================================
-- 之前：发素材只能发文字和图片，图片还是压缩过的；原图、大量图片、文档都没法发，只能另找网盘。
-- 现在：门店在「发素材」里可以添加最多 5 个文件一起发；接单人在任务交付页能打开文档、把压缩包转发到微信聊天，或复制下载链接用浏览器下载。
-- material_id 为空表示「已上传、还没发出」；发送时一并写上这次素材的 ID。服务器从不解压这些文件。
create table if not exists kg_online_material_file (
  file_id      bigint       not null auto_increment comment '附件ID',
  app_id       bigint       not null                comment '接单（kg_application.app_id）',
  store_id     bigint       not null                comment '门店',
  material_id  bigint       null                    comment '随哪次发素材发出（kg_online_material.material_id）；空 = 已上传未发送',
  file_key     varchar(96)  not null                comment 'kg-private 下的相对路径 omat/{app_id}/{32位hex}.{扩展名}',
  file_name    varchar(120) not null                comment '原文件名（只用于显示和下载时命名）',
  file_ext     varchar(8)   not null                comment '扩展名（小写）',
  file_size    bigint       not null                comment '字节数',
  uploaded_by  bigint       not null                comment '上传的员工（kg_app_user.user_id）',
  create_time  datetime     not null                comment '上传时间',
  del_flag     char(1)      not null default '0'    comment '0 正常 / 2 已清理（没发出去的超时清掉，或门店移除）',
  primary key (file_id),
  unique key uk_mfile_key (file_key),
  key idx_mfile_material (material_id),
  key idx_mfile_app (app_id, del_flag, create_time),
  key idx_mfile_store (store_id, create_time)
) engine=innodb comment='线上任务素材附件（私有存储）';
call kg_assert_same_collation('kg_online_material_file');

-- =============================================================================
-- 二、三、退回修改：长一些的说明 + 截图
-- =============================================================================
-- 之前：退回只有一句话（200 字），想指出「哪里要改」只能另外去私信发截图。
-- 现在：退回说明最多 5000 字，可以附最多 9 张截图；接单人在交付页看到原因和截图后重新提交。
-- review_comment 与验收评语共用：通过时的评语仍限 200 字（程序里判断），退回说明放宽到 5000 字。表很小，改列类型的锁可以忽略。
alter table kg_online_submission modify column review_comment text null comment '验收评语 / 退回原因（退回说明最多 5000 字，评语 200 字）';
call kg_add_col_if_absent('kg_online_submission', 'review_images', 'review_images varchar(2000) not null default '''' comment ''退回修改附的截图（逗号分隔的平台图片路径，最多 9 张）'' after review_comment');

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
values (41, 'online_files', now(), '9c2c6603d0a8fde5d04db06838429d861f2818041ac4c34111b7b12dabbb4c3b')
on duplicate key update name = 'online_files', applied_time = now(), checksum = '9c2c6603d0a8fde5d04db06838429d861f2818041ac4c34111b7b12dabbb4c3b';
