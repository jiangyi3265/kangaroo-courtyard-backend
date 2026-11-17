-- V6 回退：删除迁移账本 kg_schema_version。
--
-- 账本只记"执行过哪些迁移、执行的是哪一版脚本"，不含业务数据，删掉不丢业务数据；
-- 删掉之后一键验收的前置检查会因为"没有迁移账本"判 BLOCKED，直到重新执行 V6。
--
-- 守卫：账本里还有 V7 起的记录时拒绝回滚。V7 起的迁移靠账本核对前置版本（kg_assert_env），
-- 它们的回滚也要删自己的账本行 —— 回滚必须按依赖倒序：先回滚它们，最后才回滚 V6。
drop procedure if exists kg_v6_rollback_guard;
delimiter $$
create procedure kg_v6_rollback_guard()
begin
    declare v_later int default 0;
    declare v_msg varchar(255);
    if exists (select 1 from information_schema.tables
               where table_schema = database() and table_name = 'kg_schema_version') then
        select count(*) into v_later from kg_schema_version where version > 6;
        if v_later > 0 then
            set v_msg = concat('[V6 回滚中止] 账本里还有 ', v_later, ' 个 V7 起的迁移记录：按倒序先回滚它们，最后回滚 V6');
            signal sqlstate '45000' set message_text = v_msg;
        end if;
    end if;
end$$
delimiter ;
call kg_v6_rollback_guard();
drop procedure if exists kg_v6_rollback_guard;

drop table if exists kg_schema_version;
