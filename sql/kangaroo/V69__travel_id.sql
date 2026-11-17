-- =============================================================================
-- V69__travel_id（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('14');

-- V69：出行证件（2026-11-03 主人：「订票以后让他提交这个身份证号」「一键导出所有投递简历面试的身份证号和姓名，还有手机号去哪家，开始时间，结束时间」）。
-- 门店确认录用后，报名人在小程序里（签约到店最后一屏、面试 / 进度页）填一次证件姓名和身份证号，一人一行，以后的报名都用这一份；
-- 义工主理人 / 城市负责人在「报名管理 → 导出名单」里按到店日期导出 Excel 时读这里（订票、买保险用）。
-- 为什么不用实名核验那套（kg_app_user.real_name / id_last4 / id_hash）：那里只存摘要和后四位、还原不出证件号，线上实名核验也没开通（kangaroo.realname.mode=none）。
-- 这里存的是本人填写、只做格式和校验位检查的证件号，不算「已实名」，不改 kg_app_user 的任何列；账号已实名的，填的证件必须和实名那张一致（服务端比对摘要）。
-- 证件号只存密文（AES-GCM，密钥由 kangaroo.file.sign-secret 派生），后四位和摘要另存；账号注销时整行删除。
-- 不依赖角色阶段；只新增一张表，不改已有数据；全部语句可重复执行。
-- 后端要在本段执行之后再发布：新代码启动时核对账本里有 V69（TravelIdSchema），没有拒绝启动。

create table if not exists kg_user_travel_id (
  user_id        bigint        not null                 comment '小程序账号（kg_app_user.user_id），一人一行',
  id_name        varchar(32)   not null                 comment '证件上的姓名',
  id_no_enc      varchar(255)  not null                 comment '身份证号密文（AES-GCM，v1:base64(iv+密文)；明文不落库）',
  id_last4       char(4)       not null                 comment '证件号后四位（页面脱敏展示用）',
  id_hash        varchar(64)   not null                 comment '证件号摘要（HMAC-SHA256，与 kg_app_user.id_hash 同一算法，用来和实名比对）',
  create_time    datetime      not null                 comment '首次提交时间',
  update_time    datetime      null                     comment '最近修改时间',
  primary key (user_id)
) engine=innodb comment='出行证件（录用后订票、买保险用；本人提交，门店义工主理人 / 城市负责人导出名单时读取）';
call kg_assert_same_collation('kg_user_travel_id');

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
values (69, 'travel_id', now(), '07a8974faf064ee7c2da5ea3b8e81fd2ebd20a9d1b7b14d487b2a31ac35c359c')
on duplicate key update name = 'travel_id', applied_time = now(), checksum = '07a8974faf064ee7c2da5ea3b8e81fd2ebd20a9d1b7b14d487b2a31ac35c359c';
