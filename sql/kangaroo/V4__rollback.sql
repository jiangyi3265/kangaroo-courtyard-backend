-- V4 回退：删除唯一约束与生成列。不回退数据纠偏（纠偏是把错的算回对的，没有回退的必要）。
alter table kg_application drop index uk_app_active_user_job;
alter table kg_application drop column active_uk;
alter table kg_store drop index uk_store_name_city;
alter table kg_store drop column name_city_uk;
alter table kg_interview_slot drop index uk_slot_store_range;
alter table kg_interview_slot drop column slot_uk;
