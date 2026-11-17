-- =============================================================================
-- V36__stay_pms_buddy_gender（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('35');

-- 旅居订单台（排房 / 房态 / 办入住退房，类似民宿 PMS）+ 找搭子同行性别要求。只新增表和列，不改动已有数据。

-- 房间 / 床位：每个房型下具体的一间房或一个床位（多人间按床位建）。库存仍以房型的「间数 / 床位数」为准，
-- 这里只用来排房：知道哪位客人住哪一间、哪一个床位。
create table if not exists kg_stay_unit (
  unit_id       bigint       not null auto_increment comment '房间/床位ID',
  store_id      bigint       not null                comment '门店',
  stay_id       bigint       not null                comment '房源',
  room_type_id  bigint       not null                comment '房型',
  unit_name     varchar(32)  not null                comment '房间号 / 床位号，如 201、201-1号床',
  sort          int          not null default 0      comment '排序',
  status        char(1)      not null default '0'    comment '0 可用 1 停用（维修、不排房）',
  remark        varchar(100) null                    comment '备注',
  create_by     varchar(64)  default ''              comment '创建者',
  create_time   datetime                             comment '创建时间',
  update_by     varchar(64)  default ''              comment '更新者',
  update_time   datetime                             comment '更新时间',
  del_flag      char(1)      not null default '0'    comment '删除标志',
  primary key (unit_id),
  key idx_unit_type (room_type_id, del_flag),
  key idx_unit_stay (stay_id, del_flag)
) engine=innodb comment='房间/床位（排房用）';
call kg_assert_same_collation('kg_stay_unit');

-- 排房记录：订单的每一间房 / 每一个床位一行，占用 [start_date, end_date) 这些晚上。
-- 换房、取消排房把旧记录置为 released；提前退房把 end_date 改成退房当天，空出后面的晚上。
create table if not exists kg_stay_assign (
  assign_id     bigint       not null auto_increment comment 'ID',
  order_id      bigint       not null                comment '旅居订单',
  order_no      varchar(32)  not null                comment '订单号',
  store_id      bigint       not null                comment '门店',
  stay_id       bigint       not null                comment '房源',
  room_type_id  bigint       not null                comment '房型',
  unit_id       bigint       not null                comment '房间/床位',
  start_date    date         not null                comment '入住日（含）',
  end_date      date         not null                comment '离店日（不含）',
  status        varchar(16)  not null default 'active' comment 'active 有效 / released 已撤销',
  create_by     varchar(64)  default ''              comment '排房人',
  create_time   datetime                             comment '排房时间',
  update_by     varchar(64)  default ''              comment '更新者',
  update_time   datetime                             comment '更新时间',
  primary key (assign_id),
  key idx_assign_order (order_id, status),
  key idx_assign_unit (unit_id, status, start_date),
  key idx_assign_stay (stay_id, status, start_date)
) engine=innodb comment='排房记录';
call kg_assert_same_collation('kg_stay_assign');

call kg_add_col_if_absent('kg_stay_order', 'staff_note', 'staff_note varchar(255) null comment ''门店内部备注（客人看不到）''');
call kg_add_col_if_absent('kg_stay_order', 'checked_out_time', 'checked_out_time datetime null comment ''门店办理退房时间''');
call kg_add_col_if_absent('kg_stay_order', 'checked_out_by', 'checked_out_by varchar(64) null comment ''办理退房员工''');
call kg_add_idx_if_absent('kg_stay_order', 'idx_so_store_dates', 'key idx_so_store_dates (store_id, check_in, check_out)');

-- 找搭子同行性别要求：'' 不限 / female 仅限女生 / male 仅限男生
call kg_add_col_if_absent('kg_post', 'gender_limit', 'gender_limit varchar(8) not null default '''' comment ''同行性别要求：空 不限 / female 仅限女生 / male 仅限男生''');

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
values (36, 'stay_pms_buddy_gender', now(), '0ccdbcca02c5a9dc280c8bbdfe68019c64bfd389b6fa1e2e522ae949f2ae2d27')
on duplicate key update name = 'stay_pms_buddy_gender', applied_time = now(), checksum = '0ccdbcca02c5a9dc280c8bbdfe68019c64bfd389b6fa1e2e522ae949f2ae2d27';
