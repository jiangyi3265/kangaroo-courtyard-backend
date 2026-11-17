-- 停止新应用后再回滚；保留所有用户及证件摘要数据。
drop procedure if exists kg_rollback_identity;
delimiter $$
create procedure kg_rollback_identity()
begin
    if database()<>'dkxy_guardtest' and coalesce(@kg_allow_identity_rollback,0)<>1 then
        signal sqlstate '45000' set message_text='V19 rollback requires stopped application and explicit authorization';
    end if;
    if exists(select 1 from kg_schema_version where version>19) then
        signal sqlstate '45000' set message_text='V19 rollback blocked by later migrations';
    end if;
    if exists(select 1 from information_schema.statistics where table_schema=database() and table_name='kg_app_user' and index_name='uk_app_active_identity') then
        alter table kg_app_user drop index uk_app_active_identity;
    end if;
    if exists(select 1 from information_schema.columns where table_schema=database() and table_name='kg_app_user' and column_name='active_id_hash') then
        alter table kg_app_user drop column active_id_hash;
    end if;
    delete from kg_schema_version where version=19;
end$$
delimiter ;
call kg_rollback_identity();
drop procedure kg_rollback_identity;
