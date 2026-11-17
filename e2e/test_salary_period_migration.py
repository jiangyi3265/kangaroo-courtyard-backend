"""V23 dictionary migration in a disposable local database; never touches business data."""
from pathlib import Path
import json
import os
import re
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from dbcred import db_password

mysql = os.environ.get('KG_MYSQL', r'D:\MySQL Server 8.0\bin\mysql.exe')
env = dict(os.environ, MYSQL_PWD=db_password(str(ROOT / 'RuoYi-Vue')))
database = 'kg_salary_test_' + uuid.uuid4().hex[:12]
assert re.fullmatch(r'kg_salary_test_[a-f0-9]{12}', database)
checks = []

def sql(statement, use_database=True, expect_error=None):
    cmd = [mysql, '--protocol=TCP', '-h127.0.0.1', '-uroot', '--default-character-set=utf8mb4', '-n', '-N', '-B']
    if use_database:
        cmd.append(database)
    result = subprocess.run(cmd, input=statement, capture_output=True, encoding='utf-8', env=env)
    if expect_error:
        assert result.returncode != 0 and expect_error in result.stderr, result.stderr
    else:
        assert result.returncode == 0, result.stderr
    return result.stdout.strip()

created = False
try:
    sql('create database ' + database + ' character set utf8mb4;', False)
    created = True
    sql('''
create table kg_schema_version (version int primary key, name varchar(128), applied_time datetime, checksum char(64));
insert into kg_schema_version values (22, 'fixture', now(), 'fixture');
create table sys_dict_data (dict_code bigint auto_increment primary key, dict_sort int, dict_label varchar(100), dict_value varchar(100), dict_type varchar(100), css_class varchar(100), list_class varchar(100), is_default char(1), status char(1), create_by varchar(64), create_time datetime, remark varchar(500));
create table kg_job (salary_type varchar(16));
insert into sys_dict_data (dict_label, dict_value, dict_type, status, remark) values ('既有时薪配置','hourly','kg_salary_type','1','existing'),('日薪','daily','kg_salary_type','0','existing');
''')
    migration = (ROOT / 'RuoYi-Vue/sql/kangaroo/V23__salary_periods.sql').read_text(encoding='utf-8')
    rollback = (ROOT / 'RuoYi-Vue/sql/kangaroo/V23__rollback.sql').read_text(encoding='utf-8')
    sql(migration); sql(migration)
    assert sql("select count(*) from sys_dict_data where dict_value='weekly'") == '1'
    assert sql("select concat(dict_label,':',status) from sys_dict_data where dict_value='hourly'") == '既有时薪配置:1'
    assert sql('select count(*) from kg_schema_version where version=23') == '1'
    checks.append('migration rerun is idempotent and preserves existing dictionary configuration')
    sql(rollback, expect_error='explicit authorization')
    checks.append('rollback requires explicit authorization')
    sql("insert into kg_job values ('weekly')")
    sql('set @kg_allow_salary_periods_rollback=1;\n' + rollback, expect_error='hourly or weekly jobs still exist')
    checks.append('rollback refuses to remove salary periods used by jobs')
    sql('delete from kg_job; insert into kg_schema_version values (24,\'fixture\',now(),\'fixture\')')
    sql('set @kg_allow_salary_periods_rollback=1;\n' + rollback, expect_error='later migrations')
    sql('delete from kg_schema_version where version=24;')
    checks.append('rollback refuses when later migrations exist')
    sql('set @kg_allow_salary_periods_rollback=1;\n' + rollback)
    assert sql("select count(*) from sys_dict_data where dict_value='weekly'") == '0'
    assert sql("select count(*) from sys_dict_data where dict_value='hourly' and remark='existing'") == '1'
    assert sql('select count(*) from kg_schema_version where version=23') == '0'
    checks.append('rollback removes only dictionary entries owned by V23')
    sql("delete from sys_dict_data where dict_value='hourly';")
    sql(migration)
    assert sql("select count(*) from sys_dict_data where dict_type='kg_salary_type' and dict_value in ('hourly','weekly') and status='0'") == '2'
    checks.append('fresh migration creates both active salary period options')
finally:
    if created:
        sql('drop database ' + database, False)

print(json.dumps({'scope': 'isolated local MySQL database, removed after test', 'checks': checks}, ensure_ascii=False, indent=2))
