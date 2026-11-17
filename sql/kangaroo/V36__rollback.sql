-- V36 回退：删除排房表、订单台新增列和找搭子性别列。
-- 已经有排房记录、房间床位、退房记录或性别要求时拒绝回退（会丢数据）；需要先停应用，并显式 set @kg_allow_stay_pms_rollback = 1。
drop procedure if exists kg_rollback_stay_pms;
delimiter $$
create procedure kg_rollback_stay_pms()
begin
    if coalesce(@kg_allow_stay_pms_rollback, 0) <> 1 then
        signal sqlstate '45000' set message_text = 'V36 rollback requires stopped application and explicit authorization';
    end if;
    if exists (select 1 from kg_schema_version where version > 36) then
        signal sqlstate '45000' set message_text = 'V36 rollback blocked by later migrations';
    end if;
    if exists (select 1 from information_schema.tables where table_schema = database() and table_name = 'kg_stay_assign') then
        if exists (select 1 from kg_stay_assign) then
            signal sqlstate '45000' set message_text = 'V36 rollback blocked: room assignments exist';
        end if;
    end if;
    if exists (select 1 from information_schema.tables where table_schema = database() and table_name = 'kg_stay_unit') then
        if exists (select 1 from kg_stay_unit) then
            signal sqlstate '45000' set message_text = 'V36 rollback blocked: rooms/beds exist';
        end if;
    end if;
    if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_stay_order' and column_name = 'staff_note') then
        if exists (select 1 from kg_stay_order where staff_note is not null or checked_out_time is not null) then
            signal sqlstate '45000' set message_text = 'V36 rollback blocked: staff notes or check-out records exist';
        end if;
    end if;
    if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_post' and column_name = 'gender_limit') then
        if exists (select 1 from kg_post where gender_limit <> '') then
            signal sqlstate '45000' set message_text = 'V36 rollback blocked: buddy gender limits exist';
        end if;
        alter table kg_post drop column gender_limit;
    end if;
    drop table if exists kg_stay_assign;
    drop table if exists kg_stay_unit;
    if exists (select 1 from information_schema.statistics where table_schema = database() and table_name = 'kg_stay_order' and index_name = 'idx_so_store_dates') then
        alter table kg_stay_order drop index idx_so_store_dates;
    end if;
    if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_stay_order' and column_name = 'staff_note') then
        alter table kg_stay_order drop column staff_note, drop column checked_out_time, drop column checked_out_by;
    end if;
    delete from kg_schema_version where version = 36;
end$$
delimiter ;
call kg_rollback_stay_pms();
drop procedure kg_rollback_stay_pms;
