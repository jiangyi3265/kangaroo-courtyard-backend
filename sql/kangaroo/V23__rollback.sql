-- 只删除本迁移新增的字典项；已有时薪/周薪岗位时禁止回滚，不能改写其金额或周期。
drop procedure if exists kg_rollback_salary_periods;
delimiter $$
create procedure kg_rollback_salary_periods()
begin
    if database() <> 'dkxy_guardtest' and coalesce(@kg_allow_salary_periods_rollback, 0) <> 1 then
        signal sqlstate '45000' set message_text = 'V23 rollback requires stopped application and explicit authorization';
    end if;
    if exists (select 1 from kg_schema_version where version > 23) then
        signal sqlstate '45000' set message_text = 'V23 rollback blocked by later migrations';
    end if;
    if exists (select 1 from kg_job where salary_type in ('hourly', 'weekly')) then
        signal sqlstate '45000' set message_text = 'V23 rollback blocked: hourly or weekly jobs still exist';
    end if;
    delete from sys_dict_data where dict_type = 'kg_salary_type'
        and dict_value in ('hourly', 'weekly') and remark = 'salary_periods_v23';
    delete from kg_schema_version where version = 23;
end$$
delimiter ;
call kg_rollback_salary_periods();
drop procedure kg_rollback_salary_periods;
