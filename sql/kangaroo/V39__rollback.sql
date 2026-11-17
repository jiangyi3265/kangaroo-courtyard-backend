-- V39 回退：删除门店退款记录表、kg_app_user.dm_privacy、kg_post.tone。
-- 已经有门店退款记录、有人改过私信权限或发过带「伙伴说」的帖子时拒绝回退（会丢数据）；需要先停应用，并显式 set @kg_allow_beta_feedback_rollback = 1。
drop procedure if exists kg_rollback_beta_feedback;
delimiter $$
create procedure kg_rollback_beta_feedback()
begin
    if coalesce(@kg_allow_beta_feedback_rollback, 0) <> 1 then
        signal sqlstate '45000' set message_text = 'V39 rollback requires stopped application and explicit authorization';
    end if;
    if exists (select 1 from kg_schema_version where version > 39) then
        signal sqlstate '45000' set message_text = 'V39 rollback blocked by later migrations';
    end if;
    if exists (select 1 from information_schema.tables where table_schema = database() and table_name = 'kg_stay_staff_refund') then
        if exists (select 1 from kg_stay_staff_refund) then
            signal sqlstate '45000' set message_text = 'V39 rollback blocked: store refund records exist';
        end if;
    end if;
    if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_app_user' and column_name = 'dm_privacy') then
        if exists (select 1 from kg_app_user where dm_privacy <> 'all') then
            signal sqlstate '45000' set message_text = 'V39 rollback blocked: users changed message privacy';
        end if;
        alter table kg_app_user drop column dm_privacy;
    end if;
    if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_post' and column_name = 'tone') then
        if exists (select 1 from kg_post where tone <> '') then
            signal sqlstate '45000' set message_text = 'V39 rollback blocked: posts use 伙伴说 tags';
        end if;
        alter table kg_post drop column tone;
    end if;
    drop table if exists kg_stay_staff_refund;
    delete from kg_schema_version where version = 39;
end$$
delimiter ;
call kg_rollback_beta_feedback();
drop procedure kg_rollback_beta_feedback;
