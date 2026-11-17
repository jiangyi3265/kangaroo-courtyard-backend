"""V26 and operations regression in an isolated local MySQL database; no live business writes."""
from pathlib import Path
import os, re, subprocess, sys, uuid, json, hashlib
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT))
from dbcred import db_password
BE=ROOT/'RuoYi-Vue'; OUT=ROOT/'artifacts/operations-completion-20261002'
database='kg_ops_test_'+uuid.uuid4().hex[:12]
assert re.fullmatch(r'kg_ops_test_[a-f0-9]{12}',database)
env=dict(os.environ,MYSQL_PWD=db_password(str(BE)),KG_OPS_TEST_DB=database,KG_OPS_META_DIR=str(OUT/'meta'))
mysql=os.environ.get('KG_MYSQL',r'D:\MySQL Server 8.0\bin\mysql.exe')
checks=[]
def sql(query,use=None,error=None):
    cmd=[mysql,'--protocol=TCP','-h127.0.0.1','-uroot','--default-character-set=utf8mb4','-N','-B','--raw']
    if use:cmd.append(use)
    r=subprocess.run(cmd,input=query,encoding='utf-8',capture_output=True,env=env)
    if error:assert r.returncode and error in r.stderr,r.stderr
    else:assert r.returncode==0,r.stderr
    return r.stdout.strip()
created=False
try:
    schemas=sql("select table_schema from information_schema.tables where table_name='kg_schema_version' and table_schema not like '%test%' and table_schema not like '%guard%'").splitlines()
    source=os.environ.get('KG_OPS_SOURCE_DB','dkxy')
    assert source in schemas,'Configured local source schema unavailable: '+repr(schemas)
    assert re.fullmatch(r'[a-zA-Z0-9_]+',source)
    collation=sql("select default_collation_name from information_schema.schemata where schema_name='"+source+"'")
    assert re.fullmatch(r'[a-zA-Z0-9_]+',collation)
    sql('create database '+database+' character set utf8mb4 collate '+collation);created=True
    tables=sql("select table_name from information_schema.tables where table_schema='"+source+"' and (table_name like 'kg_%' or table_name in ('sys_menu','sys_role_menu'))").splitlines()
    # Only definitions are read from the local database. Business rows are never copied.
    for table in tables:
        assert re.fullmatch(r'[a-zA-Z0-9_]+',table)
        ddl=sql('show create table `'+table+'`',source).split('\t',1)[1]
        sql(ddl,database)
    sql("insert into kg_schema_version(version,name,applied_time,checksum) values(25,'fixture',now(),'fixture'); insert into sys_menu(menu_id,menu_name,parent_id,path,menu_type,perms) values(900001,'用户服务',0,'kgusersvc','M',''),(900002,'社区活动',0,'activity','C','kangaroo:activity:list');",database)
    migration=(BE/'sql/kangaroo/V26__operations_completion.sql').read_text(encoding='utf-8')
    pre,ledger=migration.split('-- @kg-ledger（本行起不计入 checksum）',1)
    assert hashlib.sha256(pre.encode()).hexdigest() in ledger
    sql(migration,database);sql(migration,database)
    assert sql('select count(*) from kg_support_faq',database)=='6'
    assert sql("select count(*) from sys_menu where perms='kangaroo:activity:add'",database)=='1'
    assert sql("select count(*) from sys_menu where perms like 'kangaroo:faq:%'",database)=='5'
    checks.append('V26 checksum, repeat migration, FAQ defaults and menu permissions')
    rollback=(BE/'sql/kangaroo/V26__rollback.sql').read_text(encoding='utf-8')
    sql(rollback,database,error='explicit authorization')
    sql('set @kg_allow_ops_rollback=1;\n'+rollback,database,error='FAQ content still exists')
    checks.append('rollback refuses unapproved execution and content loss')
    (OUT/'meta').mkdir(exist_ok=True)
    with (OUT/'backend-tests.log').open('w',encoding='utf-8') as log:
        r=subprocess.run(['mvn.cmd','-o','-pl','ruoyi-kangaroo','-am','-Dtest=KgOperationsIT,KgResumeForwardTest','-Dsurefire.failIfNoSpecifiedTests=false','-Duser.timezone=Asia/Shanghai','test'],cwd=BE,env=env,stdout=log,stderr=subprocess.STDOUT)
    assert r.returncode==0,'Backend regression failed; see backend-tests.log'
    checks.append('real JDBC operations and existing within-store forwarding regression')
    sql('delete from kg_support_faq; update kg_resume_forward set target_store_id=null,target_store_name=null;update kg_interview_slot set meeting_info=null;',database)
    sql('set @kg_allow_ops_rollback=1;\n'+rollback,database);sql(migration,database)
    checks.append('explicit empty-data rollback and reapply')
finally:
    if created:sql('drop database '+database)
(OUT/'database-result.json').write_text(json.dumps({'scope':'isolated local MySQL; temporary database removed','checks':checks},ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(checks,ensure_ascii=False))
