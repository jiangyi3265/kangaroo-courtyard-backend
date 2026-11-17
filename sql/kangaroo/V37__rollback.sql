-- V37 回退：删除义工类型、小程序公告、任务素材、积分加急 / 义工完成 / 邀请、私信交换联系方式新增的表、列、索引、字典、菜单和参数。
-- 已经有人用过这些新功能（发过素材、加急过、绑定过邀请人、同意过交换联系方式、门店确认过 / 系统自动完成过义工、岗位选了义工类型、
-- 新建过公告）时拒绝回退（会丢数据）；需要先停应用，并显式 set @kg_allow_points_notice_rollback = 1。
-- 已经记进 kg_point_entry 的积分流水保留不动（老版本照样按流水合计积分）。
drop procedure if exists kg_rollback_points_notice;
delimiter $$
create procedure kg_rollback_points_notice()
begin
    if coalesce(@kg_allow_points_notice_rollback, 0) <> 1 then
        signal sqlstate '45000' set message_text = 'V37 rollback requires stopped application and explicit authorization';
    end if;
    if exists (select 1 from kg_schema_version where version > 37) then
        signal sqlstate '45000' set message_text = 'V37 rollback blocked by later migrations';
    end if;
    if exists (select 1 from information_schema.tables where table_schema = database() and table_name = 'kg_online_material') then
        if exists (select 1 from kg_online_material) then
            signal sqlstate '45000' set message_text = 'V37 rollback blocked: task materials exist';
        end if;
    end if;
    if exists (select 1 from information_schema.tables where table_schema = database() and table_name = 'kg_app_notice') then
        if exists (select 1 from kg_app_notice where notice_key is null or notice_key <> 'online-guide' or version > 1) then
            signal sqlstate '45000' set message_text = 'V37 rollback blocked: notices were created or edited';
        end if;
    end if;
    if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_application' and column_name = 'urgent_flag') then
        if exists (select 1 from kg_application where urgent_flag = '1' or volunteer_done_by like 'staff:%' or volunteer_done_by = 'system') then
            signal sqlstate '45000' set message_text = 'V37 rollback blocked: urgent applications or volunteer completions exist';
        end if;
    end if;
    if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_app_user' and column_name = 'invited_by') then
        if exists (select 1 from kg_app_user where invited_by is not null) then
            signal sqlstate '45000' set message_text = 'V37 rollback blocked: invite bindings exist';
        end if;
    end if;
    if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_chat_member' and column_name = 'contact_ok_time') then
        if exists (select 1 from kg_chat_member where contact_ok_time is not null) then
            signal sqlstate '45000' set message_text = 'V37 rollback blocked: contact exchange consents exist';
        end if;
    end if;
    if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_job' and column_name = 'work_kind') then
        if exists (select 1 from kg_job where work_kind <> '') then
            signal sqlstate '45000' set message_text = 'V37 rollback blocked: jobs already have volunteer kinds';
        end if;
    end if;

    drop table if exists kg_app_notice_seen;
    drop table if exists kg_app_notice;
    drop table if exists kg_online_material;

    if exists (select 1 from information_schema.statistics where table_schema = database() and table_name = 'kg_job' and index_name = 'idx_job_kind') then
        alter table kg_job drop index idx_job_kind;
    end if;
    if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_job' and column_name = 'work_kind') then
        alter table kg_job drop column work_kind;
    end if;
    if exists (select 1 from information_schema.statistics where table_schema = database() and table_name = 'kg_application' and index_name = 'idx_app_volunteer_done') then
        alter table kg_application drop index idx_app_volunteer_done;
    end if;
    if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_application' and column_name = 'urgent_flag') then
        alter table kg_application drop column urgent_flag, drop column urgent_time, drop column volunteer_done_time, drop column volunteer_days, drop column volunteer_done_by;
    end if;
    if exists (select 1 from information_schema.statistics where table_schema = database() and table_name = 'kg_app_user' and index_name = 'uk_app_user_invite_code') then
        alter table kg_app_user drop index uk_app_user_invite_code;
    end if;
    if exists (select 1 from information_schema.statistics where table_schema = database() and table_name = 'kg_app_user' and index_name = 'idx_app_user_invited_by') then
        alter table kg_app_user drop index idx_app_user_invited_by;
    end if;
    if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_app_user' and column_name = 'invite_code') then
        alter table kg_app_user drop column invite_code, drop column invited_by, drop column invited_time;
    end if;
    if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_chat_member' and column_name = 'contact_ok_time') then
        alter table kg_chat_member drop column contact_ok_time;
    end if;
    if exists (select 1 from information_schema.statistics where table_schema = database() and table_name = 'kg_online_settlement' and index_name = 'idx_online_settle_user') then
        alter table kg_online_settlement drop index idx_online_settle_user;
    end if;
    if exists (select 1 from information_schema.statistics where table_schema = database() and table_name = 'kg_job_subscribe' and index_name = 'idx_jsub_store') then
        alter table kg_job_subscribe drop index idx_jsub_store;
    end if;

    delete from sys_role_menu where menu_id in (select menu_id from (select menu_id from sys_menu where perms like 'kangaroo:appnotice:%') x);
    delete from sys_menu where perms like 'kangaroo:appnotice:%';
    delete from sys_dict_data where dict_type = 'kg_volunteer_kind';
    delete from sys_dict_type where dict_type = 'kg_volunteer_kind';
    delete from sys_config where config_key in ('kangaroo.points.volunteerPerDay', 'kangaroo.points.inviteReward', 'kangaroo.points.urgentCost');
    delete from kg_schema_version where version = 37;
end$$
delimiter ;
call kg_rollback_points_notice();
drop procedure kg_rollback_points_notice;
