-- =============================================================================
-- V29__home_membership_ops（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
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
call kg_assert_env('28');

-- 首页运营与成长会员权益。空表不伪造推荐或折扣；等级沿用现有门槛。
create table if not exists kg_home_pick (
 pick_id bigint primary key auto_increment, section varchar(16) not null,
 target_id bigint not null, target_title varchar(128) not null default '', city varchar(32) not null default '',
 sort int not null default 0, status char(1) not null default '0',
 start_time datetime null, end_time datetime null, create_by varchar(64) default '', create_time datetime default current_timestamp, update_by varchar(64) default '', update_time datetime null, del_flag char(1) not null default '0',
 index idx_home_scope(section,city,status,del_flag)
) engine=innodb;
create table if not exists kg_member_level (
 level_id bigint primary key, level_name varchar(32) not null, min_points bigint not null,
 description varchar(255) default '', create_by varchar(64) default '', create_time datetime default current_timestamp, update_by varchar(64) default '', update_time datetime null, del_flag char(1) not null default '0'
) engine=innodb;
insert ignore into kg_member_level(level_id,level_name,min_points,create_by) values
 (1,'幼崽鼠',0,'V29'),(2,'小袋鼠',100,'V29'),(3,'袋鼠',300,'V29'),
 (4,'大袋鼠',800,'V29'),(5,'巨袋鼠',2000,'V29'),(6,'袋鼠王',5000,'V29');
create table if not exists kg_member_benefit (
 benefit_id bigint primary key auto_increment, merchant_name varchar(64) not null,
 title varchar(64) not null, city varchar(32) not null, category varchar(16) not null,
 cover varchar(512) default '', discount_text varchar(64) not null,
 min_level int not null default 0, rules text not null, address varchar(255) default '',
 phone varchar(32) default '', start_time datetime null, end_time datetime null,
 status char(1) not null default '1', sort int not null default 0, create_by varchar(64) default '', create_time datetime default current_timestamp, update_by varchar(64) default '', update_time datetime null, del_flag char(1) not null default '0',
 index idx_benefit_search(status,del_flag,city,category)
) engine=innodb;
call kg_assert_same_collation('kg_home_pick');
call kg_assert_same_collation('kg_member_level');
call kg_assert_same_collation('kg_member_benefit');
insert into sys_menu(menu_id,menu_name,parent_id,order_num,path,component,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
select 3900,'首页内容管理',2000,3900,'homepick','kangaroo/ext/homepick/index','HomepageContent',1,0,'C','0','0','kangaroo:homepick:list','star','admin',now()
where not exists(select 1 from sys_menu where menu_id=3900);
insert into sys_menu(menu_id,menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
select 3901,'首页内容管理query',3900,1,'',1,0,'F','0','0','kangaroo:homepick:query','#','admin',now()
where not exists(select 1 from sys_menu where menu_id=3901);
insert into sys_menu(menu_id,menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
select 3902,'首页内容管理add',3900,2,'',1,0,'F','0','0','kangaroo:homepick:add','#','admin',now()
where not exists(select 1 from sys_menu where menu_id=3902);
insert into sys_menu(menu_id,menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
select 3903,'首页内容管理edit',3900,3,'',1,0,'F','0','0','kangaroo:homepick:edit','#','admin',now()
where not exists(select 1 from sys_menu where menu_id=3903);
insert into sys_menu(menu_id,menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
select 3904,'首页内容管理remove',3900,4,'',1,0,'F','0','0','kangaroo:homepick:remove','#','admin',now()
where not exists(select 1 from sys_menu where menu_id=3904);
insert ignore into sys_role_menu(role_id,menu_id) select distinct rm.role_id,m.menu_id from sys_role_menu rm join sys_menu m on m.menu_id between 3900 and 3904 where rm.menu_id=2000;
insert into sys_menu(menu_id,menu_name,parent_id,order_num,path,component,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
select 3920,'会员等级设置',2000,3920,'memberlevel','kangaroo/ext/memberlevel/index','MemberLevel',1,0,'C','0','0','kangaroo:memberlevel:list','star','admin',now()
where not exists(select 1 from sys_menu where menu_id=3920);
insert into sys_menu(menu_id,menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
select 3921,'会员等级设置query',3920,1,'',1,0,'F','0','0','kangaroo:memberlevel:query','#','admin',now()
where not exists(select 1 from sys_menu where menu_id=3921);
insert into sys_menu(menu_id,menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
select 3922,'会员等级设置edit',3920,2,'',1,0,'F','0','0','kangaroo:memberlevel:edit','#','admin',now()
where not exists(select 1 from sys_menu where menu_id=3922);
insert ignore into sys_role_menu(role_id,menu_id) select distinct rm.role_id,m.menu_id from sys_role_menu rm join sys_menu m on m.menu_id between 3920 and 3922 where rm.menu_id=2000;
insert into sys_menu(menu_id,menu_name,parent_id,order_num,path,component,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
select 3940,'会员店铺权益',2000,3940,'memberbenefit','kangaroo/ext/memberbenefit/index','MemberBenefit',1,0,'C','0','0','kangaroo:memberbenefit:list','star','admin',now()
where not exists(select 1 from sys_menu where menu_id=3940);
insert into sys_menu(menu_id,menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
select 3941,'会员店铺权益query',3940,1,'',1,0,'F','0','0','kangaroo:memberbenefit:query','#','admin',now()
where not exists(select 1 from sys_menu where menu_id=3941);
insert into sys_menu(menu_id,menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
select 3942,'会员店铺权益add',3940,2,'',1,0,'F','0','0','kangaroo:memberbenefit:add','#','admin',now()
where not exists(select 1 from sys_menu where menu_id=3942);
insert into sys_menu(menu_id,menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
select 3943,'会员店铺权益edit',3940,3,'',1,0,'F','0','0','kangaroo:memberbenefit:edit','#','admin',now()
where not exists(select 1 from sys_menu where menu_id=3943);
insert into sys_menu(menu_id,menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
select 3944,'会员店铺权益remove',3940,4,'',1,0,'F','0','0','kangaroo:memberbenefit:remove','#','admin',now()
where not exists(select 1 from sys_menu where menu_id=3944);
insert ignore into sys_role_menu(role_id,menu_id) select distinct rm.role_id,m.menu_id from sys_role_menu rm join sys_menu m on m.menu_id between 3940 and 3944 where rm.menu_id=2000;

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
values (29, 'home_membership_ops', now(), '1c104207988a7b185d418f4e8aa1e996ae0af3ea8843b083094d669370721dcb')
on duplicate key update name = 'home_membership_ops', applied_time = now(), checksum = '1c104207988a7b185d418f4e8aa1e996ae0af3ea8843b083094d669370721dcb';
