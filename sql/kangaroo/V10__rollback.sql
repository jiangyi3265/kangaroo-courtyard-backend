-- V10 回退：社区（动态、评论、关注拉黑、活动与报名、公开行程、私信群聊、敏感词、规则同意）。回退前先备份这些表。
-- 活动的在线支付单留在 kg_pay_order（biz_type = activity_signup），不随本回退删除，便于对账。
delete from sys_role_menu where menu_id between 2300 and 2399;
delete from sys_menu where menu_id between 2300 and 2399;
drop table if exists kg_pii_access;
drop table if exists kg_consent;
drop table if exists kg_sensitive_word;
drop table if exists kg_chat_msg;
drop table if exists kg_chat_member;
drop table if exists kg_chat_conv;
drop table if exists kg_trip_plan;
drop table if exists kg_activity_subscribe;
drop table if exists kg_activity_stat;
drop table if exists kg_activity_signup;
drop table if exists kg_activity;
drop table if exists kg_user_block;
drop table if exists kg_user_follow;
drop table if exists kg_post_comment_like;
drop table if exists kg_post_comment;
drop table if exists kg_post_like;
drop table if exists kg_post;
delete from kg_schema_version where version = 10;
