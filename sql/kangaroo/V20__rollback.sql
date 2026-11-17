-- 先停止新应用。待办/发送中/待核对记录不能删除，必须先核对投递结果。
drop procedure if exists kg_rollback_subscribe_outbox;
delimiter $$
create procedure kg_rollback_subscribe_outbox()
begin
    if database()<>'dkxy_guardtest' and coalesce(@kg_allow_outbox_rollback,0)<>1 then
        signal sqlstate '45000' set message_text='V20 rollback requires stopped application and explicit authorization';
    end if;
    if exists(select 1 from kg_schema_version where version>20) then
        signal sqlstate '45000' set message_text='V20 rollback blocked by later migrations';
    end if;
    if exists(select 1 from information_schema.tables where table_schema=database() and table_name='kg_subscribe_outbox') then
        if exists(select 1 from kg_subscribe_outbox where status in ('queued','sending','unknown')) then
            signal sqlstate '45000' set message_text='V20 has unresolved delivery records; resolve and archive them before rollback';
        end if;
        drop table kg_subscribe_outbox;
    end if;
    delete from kg_schema_version where version=20;
end$$
delimiter ;
call kg_rollback_subscribe_outbox();
drop procedure kg_rollback_subscribe_outbox;
