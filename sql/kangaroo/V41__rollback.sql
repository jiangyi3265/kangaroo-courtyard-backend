-- V41 回退：删除线上任务素材附件表 kg_online_material_file，撤掉 kg_online_submission.review_images，review_comment 改回 varchar(200)。
-- 只要还有附件记录（包括已清理的）、退回截图，或者有超过 200 字的退回说明，就拒绝回退（会丢数据）；
-- 需要先停应用，并显式 set @kg_allow_online_files_rollback = 1。
-- 附件文件本身在服务器 kg-private/omat/ 目录里，回退不会删除；确认不要了再手动清理。
drop procedure if exists kg_rollback_online_files;
delimiter $$
create procedure kg_rollback_online_files()
begin
    if coalesce(@kg_allow_online_files_rollback, 0) <> 1 then
        signal sqlstate '45000' set message_text = 'V41 rollback requires stopped application and explicit authorization';
    end if;
    if exists (select 1 from kg_schema_version where version > 41) then
        signal sqlstate '45000' set message_text = 'V41 rollback blocked by later migrations';
    end if;
    if exists (select 1 from information_schema.tables where table_schema = database() and table_name = 'kg_online_material_file') then
        if exists (select 1 from kg_online_material_file) then
            signal sqlstate '45000' set message_text = 'V41 rollback blocked: material attachments still exist';
        end if;
    end if;
    if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_online_submission' and column_name = 'review_images') then
        if exists (select 1 from kg_online_submission where review_images <> '') then
            signal sqlstate '45000' set message_text = 'V41 rollback blocked: return screenshots still exist';
        end if;
    end if;
    if exists (select 1 from kg_online_submission where char_length(review_comment) > 200) then
        signal sqlstate '45000' set message_text = 'V41 rollback blocked: return reasons longer than 200 characters exist';
    end if;
    drop table if exists kg_online_material_file;
    if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_online_submission' and column_name = 'review_images') then
        alter table kg_online_submission drop column review_images;
    end if;
    if exists (select 1 from information_schema.columns where table_schema = database() and table_name = 'kg_online_submission'
               and column_name = 'review_comment' and column_type <> 'varchar(200)') then
        alter table kg_online_submission modify column review_comment varchar(200) null comment '验收评语 / 退回原因';
    end if;
    delete from kg_schema_version where version = 41;
end$$
delimiter ;
call kg_rollback_online_files();
drop procedure kg_rollback_online_files;
