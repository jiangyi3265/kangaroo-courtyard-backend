"""V24 migration + real JDBC/MyBatis roundtrip in a disposable local MySQL database."""
from pathlib import Path
import os, re, subprocess, sys, uuid, json
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from dbcred import db_password
BE = ROOT / 'RuoYi-Vue'
database = 'kg_comp_test_' + uuid.uuid4().hex[:12]
assert re.fullmatch(r'kg_comp_test_[a-f0-9]{12}', database)
env = dict(os.environ, MYSQL_PWD=db_password(str(BE)), KG_COMPENSATION_TEST_DB=database)
mysql = os.environ.get('KG_MYSQL', r'D:\MySQL Server 8.0\bin\mysql.exe')
checks = []
def sql(text, use_db=True, error=None):
    cmd = [mysql, '--protocol=TCP', '-h127.0.0.1', '-uroot', '--default-character-set=utf8mb4', '-n', '-N', '-B']
    if use_db: cmd.append(database)
    p = subprocess.run(cmd, input=text, encoding='utf-8', capture_output=True, env=env)
    if error: assert p.returncode and error in p.stderr, p.stderr
    else: assert p.returncode == 0, p.stderr
    return p.stdout.strip()
created = False
try:
    sql('create database ' + database + ' character set utf8mb4;', False); created = True
    core = (BE / 'sql/kangaroo/V1__kangaroo_core.sql').read_text(encoding='utf-8')
    job_ddl = re.search(r'create table kg_job \([\s\S]+?\) engine=innodb[^;]*;', core).group(0)
    sql(job_ddl)
    sql((BE / 'sql/kangaroo/V3__job_display_fields.sql').read_text(encoding='utf-8'))
    sql("""
alter table kg_job add source varchar(16) default 'admin';
create table kg_store (store_id bigint primary key, store_name varchar(64), city varchar(32), address varchar(255), intro varchar(255), cover varchar(255), safe_cert char(1), del_flag char(1) default '0');
insert into kg_store values (102, '组合薪资测试门店','平潭','海边','测试','', '1','0');
create table kg_schema_version (version int primary key, name varchar(128), applied_time datetime, checksum char(64));
insert into kg_schema_version values (23,'fixture',now(),'fixture');
insert into kg_job (store_id,job_type,title,city,salary_type,salary_min,salary_max,salary_unit) values (102,'parttime','旧日薪','平潭','daily',150,150,'元/天');
""")
    migrate = (BE / 'sql/kangaroo/V24__job_compensation.sql').read_text(encoding='utf-8')
    rollback = (BE / 'sql/kangaroo/V24__rollback.sql').read_text(encoding='utf-8')
    sql(migrate); sql(migrate)
    assert sql("select count(*) from kg_job where title='旧日薪' and salary_type='daily' and salary_min=150 and bonus_desc is null and commission_desc is null") == '1'
    checks.append('V24 reruns safely and preserves existing salaries')
    sql(rollback, error='explicit authorization')
    checks.append('rollback requires explicit authorization')
    result = subprocess.run(['mvn.cmd', '-pl', 'ruoyi-kangaroo', '-am', '-Dtest=KgJobCompensationIT,KgStaffJobSalaryTest,KgJobSalaryTest,KgHomeSalaryTest', '-Dsurefire.failIfNoSpecifiedTests=false', 'test'], cwd=BE, env=env)
    assert result.returncode == 0, 'Database roundtrip or regression tests failed'
    checks.append('actual staff JDBC create, admin MyBatis insert/edit/clear, app serialization for all four periods')
    sql('set @kg_allow_job_compensation_rollback=1;\n' + rollback, error='compensation rules still exist')
    checks.append('rollback refuses to discard saved bonus or commission rules')
    sql('update kg_job set bonus_desc=null, commission_desc=null;')
    sql('set @kg_allow_job_compensation_rollback=1;\n' + rollback)
    assert sql("select count(*) from information_schema.columns where table_schema=database() and table_name='kg_job' and column_name in ('bonus_desc','commission_desc')") == '0'
    assert sql('select count(*) from kg_schema_version where version=24') == '0'
    sql(migrate)
    assert sql("select count(*) from information_schema.columns where table_schema=database() and table_name='kg_job' and column_name in ('bonus_desc','commission_desc')") == '2'
    checks.append('empty-data rollback and reapply succeed')
finally:
    if created: sql('drop database ' + database, False)
print(json.dumps({'scope':'isolated local MySQL, database removed after test','checks':checks}, ensure_ascii=False, indent=2))
