"""Verify V119 and camp SQL in a disposable local MySQL schema only."""
from pathlib import Path
import os,re,subprocess,sys,uuid,hashlib,json
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT))
from dbcred import db_password
BE=ROOT/'RuoYi-Vue'
out=ROOT/'artifacts/camp-20261117'
database='kg_camp_test_'+uuid.uuid4().hex[:12]
assert re.fullmatch(r'kg_camp_test_[a-f0-9]{12}',database)
env=dict(os.environ,MYSQL_PWD=db_password(str(BE)),KG_CAMP_TEST_DB=database)
mysql=os.environ.get('KG_MYSQL',r'D:\MySQL Server 8.0\bin\mysql.exe')
def sql(text,use_db=True):
    args=[mysql,'--protocol=TCP','-h127.0.0.1','-uroot','--default-character-set=utf8mb4','-n','-N','-B']
    if use_db:args.append(database)
    run=subprocess.run(args,input=text,encoding='utf8',capture_output=True,env=env)
    assert run.returncode==0,run.stderr
    return run.stdout.strip()
created=False
try:
    sql('create database '+database+' character set utf8mb4;',False);created=True
    for file,tables in [('V1__kangaroo_core.sql',['kg_store','kg_application','kg_job']),('V9__ext_stays.sql',['kg_stay']),('V11__ext_user.sql',['kg_city']),('V10__ext_community.sql',['kg_activity','kg_activity_signup'])]:
        text=(BE/'sql/kangaroo'/file).read_text(encoding='utf8')
        for table in tables:
            sql(re.search(r'create table (?:if not exists )?'+table+r'\s*\([\s\S]+?\)\s*engine[^;]*;',text,re.I).group(0))
    sql("alter table kg_store add op_type varchar(16) default 'direct'; create table sys_config(config_key varchar(100),config_value varchar(100)); insert into sys_config values('kangaroo.group_chat.enabled','true'); create table kg_schema_version(version int primary key,name varchar(128),applied_time datetime,checksum char(64)); insert into kg_schema_version values(94,'fixture',now(),'fixture');")
    sql("insert into kg_store(store_id,store_name,city,op_type) values(1,'A','成都','direct'),(2,'B','杭州','managed'); insert into kg_stay(stay_id,store_id,stay_name,city) values(1,1,'A','成都'),(2,2,'B','杭州');")
    migration=(BE/'sql/kangaroo/V119__camp_and_booking_channel.sql').read_text(encoding='utf8')
    assert hashlib.sha256(migration.split('-- @kg-ledger')[0].encode()).hexdigest() in migration.split('-- @kg-ledger')[1]
    sql(migration)
    assert sql('select house_type from kg_stay order by stay_id')=='direct\nmanaged'
    sql("update kg_stay set house_type='managed' where stay_id=1")
    sql(migration)
    assert sql('select house_type from kg_stay where stay_id=1')=='managed'
    sql("insert into kg_city(name,camp_local_qr,camp_destination_qr,status) values('成都','/profile/upload/local.png','/profile/upload/destination.png','0'),('大理','','/profile/upload/dali.png','0'),('杭州','','/profile/upload/hz.png','0');")
    # Use real application and activity schemas; unrelated users and cancelled signups cannot win.
    sql("insert into kg_job(job_id,store_id,title,job_type,city) values(1,1,'fixture','volunteer','杭州'); insert into kg_application(arrival_date,stay_days,real_name,age,phone,app_no,app_id,job_id,store_id,app_user_id,status,submit_time,create_time) values('2026-02-01',7,'测试',25,'13800000000','TEST-CAMP-1',1,1,1,7,'submitted','2026-01-01','2026-01-01');")
    sql("insert into kg_activity(source,activity_id,title,city,start_time) values('yard',1,'fixture','大理','2026-01-03'); insert into kg_activity_signup(signup_no,activity_id,app_user_id,status,create_time) values('TEST-CAMP-SIGNUP',1,7,'confirmed','2026-01-02');")
    with (out/'database-test.log').open('w',encoding='utf8') as log:
        run=subprocess.run(['mvn.cmd','-q','-pl','ruoyi-kangaroo','-am','test','-Dtest=KgCampMysqlIT','-Dsurefire.failIfNoSpecifiedTests=false'],cwd=BE,env=env,stdout=log,stderr=subprocess.STDOUT)
    assert run.returncode==0,'Database integration test failed; see database-test.log'
    result={'scope':'isolated local MySQL','checks':['V119 checksum','migration backfill','repeat migration preserves explicit type','real signup SQL, user isolation, latest browse comparison','live QR replacement and disabled city'],'passed':True}
    (out/'database-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf8')
    print(json.dumps(result,ensure_ascii=False))
finally:
    if created:sql('drop database '+database,False)
