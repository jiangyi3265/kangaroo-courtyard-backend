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


call kg_assert_env('25');
create table if not exists kg_support_faq (
 faq_id bigint not null auto_increment primary key,
 question varchar(80) not null,
 category varchar(16) not null default 'other',
 answer varchar(2000) not null,
 sort int not null default 0,
 status char(1) not null default '0',
 del_flag char(1) not null default '0',
 create_by varchar(64) default '', create_time datetime,
 update_by varchar(64) default '', update_time datetime
) engine=innodb comment='运营维护的常见问题';
call kg_assert_same_collation('kg_support_faq');
insert into kg_support_faq (question,category,answer,sort,status,create_by,create_time) select '报名后在哪里查看审核结果？','apply','进入“我的 → 我的简历”或报名记录查看审核进度；有进展时会收到消息通知。',1,'0','admin',now() where not exists (select 1 from kg_support_faq where question='报名后在哪里查看审核结果？');
insert into kg_support_faq (question,category,answer,sort,status,create_by,create_time) select '没有可选的面试时间怎么办？','interview','请联系岗位所属门店的主理人安排时段；门店新增可预约时段后，返回审核进度页面刷新选择。',2,'0','admin',now() where not exists (select 1 from kg_support_faq where question='没有可选的面试时间怎么办？');
insert into kg_support_faq (question,category,answer,sort,status,create_by,create_time) select '责任书在哪里签署？','contract','双方完成录用确认后，在该报名的审核进度页面按提示签署责任书。请先阅读具体内容再确认。',3,'0','admin',now() where not exists (select 1 from kg_support_faq where question='责任书在哪里签署？');
insert into kg_support_faq (question,category,answer,sort,status,create_by,create_time) select '怎样查看保险信息？','insurance','请从报名进度或保险相关消息查看实际投保状态。提交申请不代表已经出单，以展示的保单登记为准。',4,'0','admin',now() where not exists (select 1 from kg_support_faq where question='怎样查看保险信息？');
insert into kg_support_faq (question,category,answer,sort,status,create_by,create_time) select '到店地址在哪里查看？','arrival','请查看报名岗位或住宿订单对应的门店地址；不确定时通过消息联系主理人。',5,'0','admin',now() where not exists (select 1 from kg_support_faq where question='到店地址在哪里查看？');
insert into kg_support_faq (question,category,answer,sort,status,create_by,create_time) select '住宿退款按什么规则计算？','refund','请打开对应住宿订单的取消或退款页面，查看下单时约定的退改规则和退款试算。',6,'0','admin',now() where not exists (select 1 from kg_support_faq where question='住宿退款按什么规则计算？');

-- Resolve parents and permissions by key; preserve all existing role grants.
insert into sys_menu (menu_name,parent_id,order_num,path,component,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time)
select '常见问题',menu_id,12,'faq','kangaroo/ext/faq/index','KgExtFaq',1,0,'C','0','0','kangaroo:faq:list','question','admin',now()
from sys_menu where path='kgusersvc' and menu_type='M' and not exists (select 1 from sys_menu where perms='kangaroo:faq:list') limit 1;
insert into sys_menu (menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time) select '查看',menu_id,9,'',1,0,'F','0','0','kangaroo:faq:query','#','admin',now() from sys_menu where perms='kangaroo:faq:list' and not exists (select 1 from sys_menu where perms='kangaroo:faq:query') limit 1;
insert into sys_menu (menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time) select '新增',menu_id,9,'',1,0,'F','0','0','kangaroo:faq:add','#','admin',now() from sys_menu where perms='kangaroo:faq:list' and not exists (select 1 from sys_menu where perms='kangaroo:faq:add') limit 1;
insert into sys_menu (menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time) select '修改',menu_id,9,'',1,0,'F','0','0','kangaroo:faq:edit','#','admin',now() from sys_menu where perms='kangaroo:faq:list' and not exists (select 1 from sys_menu where perms='kangaroo:faq:edit') limit 1;
insert into sys_menu (menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time) select '删除',menu_id,9,'',1,0,'F','0','0','kangaroo:faq:remove','#','admin',now() from sys_menu where perms='kangaroo:faq:list' and not exists (select 1 from sys_menu where perms='kangaroo:faq:remove') limit 1;
insert into sys_menu (menu_name,parent_id,order_num,path,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time) select '新增',menu_id,9,'',1,0,'F','0','0','kangaroo:activity:add','#','admin',now() from sys_menu where perms='kangaroo:activity:list' and not exists (select 1 from sys_menu where perms='kangaroo:activity:add') limit 1;
insert into sys_role_menu(role_id,menu_id) select 1,m.menu_id from sys_menu m where (m.perms like 'kangaroo:faq:%' or m.perms='kangaroo:activity:add') and not exists(select 1 from sys_role_menu r where r.role_id=1 and r.menu_id=m.menu_id);

call kg_add_col_if_absent('kg_resume_forward','target_store_id','target_store_id bigint null comment ''跨店接收门店''');
call kg_add_col_if_absent('kg_resume_forward','target_store_name','target_store_name varchar(64) null comment ''接收门店名称''');
call kg_add_col_if_absent('kg_interview_slot','meeting_info','meeting_info varchar(500) null comment ''提供给已预约候选人的会议信息''');

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
insert into kg_schema_version (version, name, applied_time, checksum)
values (26, 'operations_completion', now(), '6984ecbd60445b07cfff131fa52e6c0f310a68e0164eec18477b7c470d238a1a')
on duplicate key update name = 'operations_completion', applied_time = now(), checksum = '6984ecbd60445b07cfff131fa52e6c0f310a68e0164eec18477b7c470d238a1a';
