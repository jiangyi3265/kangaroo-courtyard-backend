-- 先停止使用 V18 的新应用，再回滚到旧应用；手机号与用户数据保持原样。
-- 隔离验收库可直接执行，其他库须显式 set @kg_allow_phone_rollback=1。
drop procedure if exists kg_rollback_phone;
delimiter $$
create procedure kg_rollback_phone()
begin
    if database() <> 'dkxy_guardtest' and coalesce(@kg_allow_phone_rollback,0) <> 1 then
        signal sqlstate '45000' set message_text='V18 rollback requires stopped application and explicit authorization';
    end if;
    if exists (select 1 from kg_schema_version where version>18) then
        signal sqlstate '45000' set message_text='V18 rollback blocked by later migrations';
    end if;
    if exists(select 1 from information_schema.statistics where table_schema=database() and table_name='kg_app_user' and index_name='uk_app_active_phone') then
        alter table kg_app_user drop index uk_app_active_phone;
    end if;
    if exists(select 1 from information_schema.columns where table_schema=database() and table_name='kg_app_user' and column_name='active_phone') then
        alter table kg_app_user drop column active_phone;
    end if;
    drop table if exists kg_auth_mutex;
    delete from kg_schema_version where version=18;
end$$
delimiter ;
call kg_rollback_phone();
drop procedure kg_rollback_phone;
