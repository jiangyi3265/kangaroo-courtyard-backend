drop procedure if exists kg_rollback_outbox_consent;
delimiter $$
create procedure kg_rollback_outbox_consent()
begin
    if database()<>'dkxy_guardtest' and coalesce(@kg_allow_outbox_consent_rollback,0)<>1 then
        signal sqlstate '45000' set message_text='V22 rollback requires stopped application and explicit authorization';
    end if;
    if exists(select 1 from kg_schema_version where version>22) then
        signal sqlstate '45000' set message_text='V22 rollback blocked by later migrations';
    end if;
    if exists(select 1 from kg_subscribe_outbox where status in ('queued','sending','unknown')) then
        signal sqlstate '45000' set message_text='V22 unresolved delivery records must be reconciled first';
    end if;
    if exists(select 1 from information_schema.columns where table_schema=database() and table_name='kg_subscribe_outbox' and column_name='grant_version') then
        alter table kg_subscribe_outbox drop index idx_outbox_retry, drop column grant_version, drop column attempts, drop column next_attempt_time;
        alter table kg_subscribe_grant drop column grant_version;
    end if;
    delete from kg_schema_version where version=22;
end$$
delimiter ;
call kg_rollback_outbox_consent();
drop procedure kg_rollback_outbox_consent;
