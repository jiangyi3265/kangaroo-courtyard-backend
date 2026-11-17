-- 停止应用后确认无奖金/提成数据才允许删除列，不自动丢弃待遇规则。
drop procedure if exists kg_rollback_job_compensation;
delimiter $$
create procedure kg_rollback_job_compensation()
begin
    if database() <> 'dkxy_guardtest' and coalesce(@kg_allow_job_compensation_rollback, 0) <> 1 then
        signal sqlstate '45000' set message_text = 'V24 rollback requires stopped application and explicit authorization';
    end if;
    if exists (select 1 from kg_schema_version where version > 24) then
        signal sqlstate '45000' set message_text = 'V24 rollback blocked by later migrations';
    end if;
    if exists (select 1 from information_schema.columns where table_schema=database() and table_name='kg_job' and column_name='bonus_desc') then
        if exists (select 1 from kg_job where coalesce(bonus_desc, '') <> '' or coalesce(commission_desc, '') <> '') then
            signal sqlstate '45000' set message_text = 'V24 rollback blocked: compensation rules still exist';
        end if;
        alter table kg_job drop column bonus_desc, drop column commission_desc;
    end if;
    delete from kg_schema_version where version = 24;
end$$
delimiter ;
call kg_rollback_job_compensation();
drop procedure kg_rollback_job_compensation;
