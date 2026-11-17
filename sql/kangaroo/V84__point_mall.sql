-- =============================================================================
-- V84__point_mall（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('83');

create table if not exists kg_point_goods (
 goods_id bigint not null auto_increment,
 title varchar(80) not null,
 cover varchar(255) not null default '',
 description varchar(2000) not null default '',
 points_cost int not null,
 stock int not null default 0,
 delivery_mode varchar(16) not null,
 pickup_place varchar(300) not null default '',
 status varchar(16) not null default 'offline',
 version int not null default 0,
 update_by varchar(80) not null,
 create_time datetime not null default current_timestamp,
 update_time datetime not null default current_timestamp on update current_timestamp,
 primary key(goods_id), key idx_point_goods_status(status,goods_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
create table if not exists kg_point_goods_order (
 order_id bigint not null auto_increment,
 app_user_id bigint not null,
 request_key varchar(64) not null,
 goods_id bigint not null,
 title varchar(80) not null,
 cover varchar(255) not null default '',
 points_cost int not null,
 delivery_mode varchar(16) not null,
 pickup_place varchar(300) not null default '',
 recipient varchar(40) not null default '',
 phone varchar(24) not null default '',
 address varchar(300) not null default '',
 status varchar(16) not null default 'pending',
 carrier varchar(40) not null default '',
 tracking_no varchar(80) not null default '',
 handle_note varchar(500) not null default '',
 handled_by varchar(80) not null default '',
 fulfilled_time datetime null,
 create_time datetime not null default current_timestamp,
 update_time datetime not null default current_timestamp on update current_timestamp,
 primary key(order_id), unique key uk_point_goods_request(app_user_id,request_key),
 key idx_point_goods_order_user(app_user_id,order_id), key idx_point_goods_order_status(status,order_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
call kg_assert_same_collation('kg_point_goods');
call kg_assert_same_collation('kg_point_goods_order');
insert into sys_menu(menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
select '积分商品与兑换',coalesce((select menu_id from (select menu_id from sys_menu where path='kangaroo' limit 1) p),0),102,'point-mall','kangaroo/point-mall/index',1,0,'C','0','0','kangaroo:pointmall:query','shopping','migration',now()
where not exists(select 1 from sys_menu where perms='kangaroo:pointmall:query');
insert into sys_menu(menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
select '管理商品及兑换履约',menu_id,1,'#',1,0,'F','0','0','kangaroo:pointmall:edit','#','migration',now() from sys_menu where perms='kangaroo:pointmall:query'
and not exists(select 1 from (select menu_id from sys_menu where perms='kangaroo:pointmall:edit') p);

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
values (84, 'point_mall', now(), 'f13d84a2d81a1615ef65b855c6e80e871b911b5feab17bc08156a68f7077ce1d')
on duplicate key update name = 'point_mall', applied_time = now(), checksum = 'f13d84a2d81a1615ef65b855c6e80e871b911b5feab17bc08156a68f7077ce1d';
