-- V62: 管理员派发主理人任务与完成回执。独立于门店排班。
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

call kg_assert_env('60');
create table if not exists kg_staff_task (
 task_id bigint not null auto_increment primary key,
 title varchar(100) not null, description text not null, due_date date null,
 store_id bigint not null, assignee_id bigint not null,
 status varchar(16) not null default 'pending', version bigint not null default 1,
 notice_version bigint not null default 1, seen_notice_version bigint not null default 0,
 request_key varchar(100) not null, request_hash char(32) not null,
 create_by varchar(64) not null, create_time datetime not null, update_time datetime not null,
 key idx_staff_task_owner (assignee_id,status,update_time),
 key idx_staff_task_status (status,update_time),
 unique key uk_staff_task_request (request_key,store_id,assignee_id)
) engine=InnoDB default charset=utf8mb4;
create table if not exists kg_staff_task_submission (
 submission_id bigint not null auto_increment primary key,task_id bigint not null,
 content text not null,image_ids varchar(512) not null,status varchar(16) not null,
 review_note varchar(1000) null,review_by varchar(64) null,review_time datetime null,create_time datetime not null,
 key idx_staff_task_submission (task_id,submission_id)
) engine=InnoDB default charset=utf8mb4;
create table if not exists kg_staff_task_file (
 file_id bigint not null auto_increment primary key,task_id bigint not null,app_user_id bigint not null,
 file_key varchar(128) not null,create_time datetime not null,
 key idx_staff_task_file (task_id,app_user_id)
) engine=InnoDB default charset=utf8mb4;
-- 与现有业务表使用相同排序规则，避免跨表查询出现排序规则冲突。
set @kg_collation=(select table_collation from information_schema.tables where table_schema=database() and table_name='kg_application');
set @kg_s=concat('alter table kg_staff_task convert to character set utf8mb4 collate ',@kg_collation);prepare kg_st from @kg_s;execute kg_st;deallocate prepare kg_st;
set @kg_s=concat('alter table kg_staff_task_submission convert to character set utf8mb4 collate ',@kg_collation);prepare kg_st from @kg_s;execute kg_st;deallocate prepare kg_st;
set @kg_s=concat('alter table kg_staff_task_file convert to character set utf8mb4 collate ',@kg_collation);prepare kg_st from @kg_s;execute kg_st;deallocate prepare kg_st;
call kg_assert_same_collation('kg_staff_task');
call kg_assert_same_collation('kg_staff_task_submission');
call kg_assert_same_collation('kg_staff_task_file');
-- 新权限只赋给系统管理员角色；其他后台角色按实际职责单独授权。
insert into sys_menu (menu_id,menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
select 3210,'主理人任务派发',2000,12,'stafftask','kangaroo/stafftask/index','','KgStaffTask',1,0,'C','0','0','kangaroo:stafftask:list','clipboard','admin',now(),'管理员派发任务，主理人图文回执，后台审核'
where not exists(select 1 from sys_menu where menu_id=3210 or perms='kangaroo:stafftask:list');
insert into sys_menu (menu_id,menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
select 3211,'任务详情',3210,1,'',null,'','',1,0,'F','0','0','kangaroo:stafftask:query','#','admin',now(),'' where not exists(select 1 from sys_menu where menu_id=3211);
insert into sys_menu (menu_id,menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
select 3212,'派发任务',3210,2,'',null,'','',1,0,'F','0','0','kangaroo:stafftask:add','#','admin',now(),'' where not exists(select 1 from sys_menu where menu_id=3212);
insert into sys_menu (menu_id,menu_name,parent_id,order_num,path,component,query,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,remark)
select 3213,'审核任务',3210,3,'',null,'','',1,0,'F','0','0','kangaroo:stafftask:audit','#','admin',now(),'' where not exists(select 1 from sys_menu where menu_id=3213);
insert ignore into sys_role_menu (role_id,menu_id) select 1,menu_id from sys_menu where perms like 'kangaroo:stafftask:%';
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
-- @kg-ledger（本行起不计入 checksum）
insert into kg_schema_version(version,name,applied_time,checksum) values(62,'staff_task_dispatch',now(),'ef137c1a99e4db0673d510ebdd8cdf2454612c6d160a1a3aa358fcead27ddcd5') on duplicate key update name='staff_task_dispatch',checksum='ef137c1a99e4db0673d510ebdd8cdf2454612c6d160a1a3aa358fcead27ddcd5';
