"""Real isolated admin username boundary regression; no writes without --execute.

Uses the actual create/edit/login APIs and reads persisted business fields. The
only SQL mutations remove this run's own unique account, role and bindings.
Run serially with the acceptance backend on 8180 and isolated Redis database 10.
"""
import argparse
from copy import deepcopy
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import sys
from urllib.parse import urlparse
import uuid

ROOT = Path(__file__).resolve().parents[2]
BOUND_MESSAGE = '用户账号长度必须介于2和20个字符之间'


def check_environment(base, database, redis_database):
    address = urlparse(base)
    if (address.scheme != 'http' or address.hostname not in ('localhost', '127.0.0.1')
            or address.port != 8180 or address.username or address.password
            or address.path not in ('', '/') or address.query or address.fragment
            or not re.fullmatch(r'dkxy_acceptance_[A-Za-z0-9_]+', database)
            or str(redis_database) != '10'):
        raise ValueError('Explicit loopback8180, dkxy_acceptance_* and Redis10 required')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--output-dir')
    args = parser.parse_args()
    if not args.execute:
        print('Prepared only: 20-character create/login succeeds; 21-character create/edit is rejected without persisted changes; unique fixture cleanup.')
        return 0
    base, database = os.environ.get('KG_BASE', ''), os.environ.get('KG_DB', '')
    check_environment(base, database, os.environ.get('KG_REDIS_DB', ''))
    if not args.output_dir:
        raise ValueError('A new evidence directory below artifacts is required')
    out = Path(args.output_dir).resolve()
    if not out.is_relative_to(ROOT / 'artifacts') or out == ROOT / 'artifacts':
        raise ValueError('Unsafe evidence directory')
    out.mkdir(parents=True, exist_ok=False)
    # Environment is checked before the shared helpers load local configuration.
    sys.path[:0] = [str(ROOT), str(ROOT / 'RuoYi-Vue/e2e')]
    import verify
    import test_kangaroo_flow as flow
    from test_restricted_role import login_as

    username = 'un' + uuid.uuid4().hex[:18]
    too_long = username + 'x'
    role_key = 'role_' + uuid.uuid4().hex[:15]
    editor_name = 'ue' + uuid.uuid4().hex[:18]
    editor_role_key = 'edit_' + uuid.uuid4().hex[:15]
    password = 'Us3r!' + uuid.uuid4().hex[:12]
    names_sql = ','.join(repr(name) for name in (username, too_long, editor_name))
    role_keys_sql = ','.join(repr(name) for name in (role_key, editor_role_key))
    report = {'started': datetime.now(timezone.utc).isoformat(), 'base': base, 'database': database,
              'scope': 'Real local admin HTTP, captcha-backed login, authorization and MySQL; no business Mock, no real messages or payment.',
              'scriptSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'checks': [], 'completed': [], 'cleanup': 'not-started', 'status': 'fail'}
    tokens, owned_ids = [], set()

    def check(name, expected, actual):
        good = expected == actual
        report['checks'].append({'name': name, 'status': 'pass' if good else 'fail',
                                 'expected': deepcopy(expected), 'actual': deepcopy(actual)})
        print(('PASS ' if good else 'FAIL ') + name, flush=True)
        if not good:
            raise AssertionError(name)

    def api(method, path, body=None, token=None):
        status, value = flow.http(method, path, body, token=token)
        check(method + ' ' + path + ' HTTP success', 200, status)
        check(method + ' ' + path + ' business success', 200, value.get('code'))
        return value

    def rejected(method, body, admin):
        status, value = flow.http(method, '/system/user', body, token=admin)
        check(method + ' invalid username validation transport', 200, status)
        check(method + ' invalid username is a validation rejection', 400, value.get('code'))
        check(method + ' rejection explains the login-compatible bounds', BOUND_MESSAGE, value.get('msg'))

    def account_snapshot(uid):
        # Excludes credentials/session data. Role/post bindings must also remain intact.
        return {'user': flow.sql('select user_id,user_name,nick_name,dept_id,email,phonenumber,sex,status,del_flag,remark '
                                 'from sys_user where user_id=' + str(uid)),
                'roles': flow.sql('select role_id from sys_user_role where user_id=' + str(uid) + ' order by role_id'),
                'posts': flow.sql('select post_id from sys_user_post where user_id=' + str(uid) + ' order by post_id')}

    def credential_snapshot(uid):
        # Compare only in memory. Do not disclose a password or its stored hash.
        return flow.sql('select sha2(password,256),pwd_update_date from sys_user where user_id=' + str(uid))

    def login_and_identify(uid):
        status, value = login_as(username, password)
        check('20-character username can actually log in', [200, 200], [status, value.get('code')])
        token = value['token']
        tokens.append(token)
        info = api('GET', '/getInfo', token=token)
        check('Login resolves the same persisted account', [uid, username], [int(info['user']['userId']), info['user']['userName']])
        check('The test account has no unrelated administrative permissions', [], info.get('permissions') or [])
        return token

    try:
        check('Selected database is exact isolated schema', database, flow.scalar('select database()'))
        check('Only isolated Redis10 used', 10, flow.REDIS_DB)
        ping = api('GET', '/app/auth/ping')['data']
        check('Mock identity environment', True, ping.get('wxMock'))
        report['runningBuild'] = ping.get('build')
        verify.assert_isolated_backend()
        report['isolationVerified'] = True
        admin = flow.admin_login()
        tokens.append(admin)
        check('No pre-existing account with either generated name', '0', flow.scalar('select count(*) from sys_user where user_name in (' + names_sql + ')'))
        check('No pre-existing role with this run key', '0', flow.scalar('select count(*) from sys_role where role_key=' + repr(role_key)))
        check('No pre-existing editor role with this run key', '0', flow.scalar('select count(*) from sys_role where role_key=' + repr(editor_role_key)))
        check('Generated accepted/rejected names have exact boundary lengths', [20, 21], [len(username), len(too_long)])
        report['fixtureStarted'] = True
        api('POST', '/system/role', {'roleName': role_key, 'roleKey': role_key, 'roleSort': 99,
                                    'dataScope': '5', 'menuCheckStrictly': True, 'deptCheckStrictly': True,
                                    'status': '0', 'menuIds': []}, token=admin)
        rid = int(flow.scalar('select role_id from sys_role where role_key=' + repr(role_key)))
        body = {'userName': username, 'nickName': 'username-boundary', 'password': password,
                'status': '0', 'roleIds': [rid]}
        api('POST', '/system/user', body, token=admin)
        uid = int(flow.scalar('select user_id from sys_user where user_name=' + repr(username)))
        owned_ids.add(uid)
        check('Exactly one accepted boundary account persisted', '1', flow.scalar('select count(*) from sys_user where user_name=' + repr(username)))
        login_and_identify(uid)
        report['completed'].append('create20-login-same-id')

        before = account_snapshot(uid)
        before_credentials = credential_snapshot(uid)
        rejected('POST', dict(body, userName=too_long), admin)
        check('21-character create left no account in the database', '0', flow.scalar('select count(*) from sys_user where user_name=' + repr(too_long)))
        check('Rejected create left the valid account and bindings unchanged', before, account_snapshot(uid))
        check('Rejected create preserves credentials', True, before_credentials == credential_snapshot(uid))
        report['completed'].append('create21-rejected-no-write')

        rejected('PUT', dict(body, userId=uid, userName=too_long, nickName='must-not-be-saved'), admin)
        check('21-character edit left the valid account and bindings unchanged', before, account_snapshot(uid))
        check('Rejected edit created no long-name account', '0', flow.scalar('select count(*) from sys_user where user_name=' + repr(too_long)))
        check('Rejected edit preserves credentials', True, before_credentials == credential_snapshot(uid))
        report['completed'].append('edit21-rejected-no-write')

        api('PUT', '/system/user', dict(body, userId=uid, nickName='valid-boundary-edit'), token=admin)
        check('Profile edit carrying a password preserves stored credentials', True, before_credentials == credential_snapshot(uid))
        check('20-character edit persisted its intended nickname', 'valid-boundary-edit', flow.scalar('select nick_name from sys_user where user_id=' + str(uid)))
        detail = api('GET', '/system/user/' + str(uid), token=admin)['data']
        check('Admin reload sees the same ID, name and edited nickname', [uid, username, 'valid-boundary-edit'],
              [int(detail['userId']), detail['userName'], detail['nickName']])
        login_and_identify(uid)
        report['completed'].append('edit20-reload-relogin')

        edit_menu = flow.sql("select menu_id from sys_menu where perms='system:user:edit' and menu_type='F'")
        check('Exactly one user-edit permission menu exists', 1, len(edit_menu))
        api('POST', '/system/role', {'roleName': editor_role_key, 'roleKey': editor_role_key, 'roleSort': 99,
            'dataScope': '1', 'menuCheckStrictly': True, 'deptCheckStrictly': True, 'status': '0',
            'menuIds': [int(edit_menu[0][0])]}, token=admin)
        editor_rid = int(flow.scalar('select role_id from sys_role where role_key=' + repr(editor_role_key)))
        api('POST', '/system/user', {'userName': editor_name, 'nickName': 'isolated-profile-editor',
            'password': password, 'status': '0', 'roleIds': [editor_rid]}, token=admin)
        editor_uid = int(flow.scalar('select user_id from sys_user where user_name=' + repr(editor_name)))
        owned_ids.add(editor_uid)
        editor_status, editor_login = login_as(editor_name, password)
        check('Editor-only account actually logs in', [200, 200], [editor_status, editor_login.get('code')])
        editor_token = editor_login['token']; tokens.append(editor_token)
        editor_info = api('GET', '/getInfo', token=editor_token)
        check('Editor has exact edit permission without reset permission or wildcard',
              ['system:user:edit'], sorted(editor_info.get('permissions') or []))
        status, denied = flow.http('PUT', '/system/user/resetPwd', {'userId': uid, 'password': password}, token=editor_token)
        check('Editor cannot use the separately protected password reset', [200, 403], [status, denied.get('code')])
        check('Rejected password reset preserves credentials', True, before_credentials == credential_snapshot(uid))
        api('PUT', '/system/user', dict(body, userId=uid, nickName='editor-boundary-edit', password='must-not-be-a-password'), token=editor_token)
        check('Editor profile change persists the intended nickname', 'editor-boundary-edit',
              flow.scalar('select nick_name from sys_user where user_id=' + str(uid)))
        check('Edit permission cannot overwrite credentials through the profile endpoint', True,
              before_credentials == credential_snapshot(uid))
        login_and_identify(uid)
        report['completed'].append('edit-only-cannot-reset-credentials')
    except BaseException as error:
        # Do not persist raw login responses, headers, tokens or passwords.
        report['errorType'] = type(error).__name__
        report['executionFailed'] = True
    finally:
        errors = []
        for token in tokens:
            try:
                status, value = flow.http('POST', '/logout', token=token)
                if status not in (200, 401) or value.get('code') not in (200, 401):
                    errors.append('logout')
            except BaseException:
                errors.append('logout-exception')
        if report.get('fixtureStarted'):
            try:
                # Recover rows committed before an interrupted API response, including
                # a wrongly accepted 21-character username on the pre-fix backend.
                users = flow.sql('select user_id,user_name from sys_user where user_name in (' + names_sql + ')')
                roles = flow.sql('select role_id from sys_role where role_key in (' + role_keys_sql + ')')
                statements = []
                for row in users:
                    owned_uid = int(row[0])
                    owned_ids.add(owned_uid)
                    statements += ['delete from sys_user_role where user_id=' + str(owned_uid),
                                   'delete from sys_user_post where user_id=' + str(owned_uid),
                                   'delete from sys_user where user_id=' + str(owned_uid) + ' and user_name in (' + names_sql + ')']
                for row in roles:
                    owned_rid = int(row[0])
                    statements += ['delete from sys_role_menu where role_id=' + str(owned_rid),
                                   'delete from sys_role_dept where role_id=' + str(owned_rid),
                                   'delete from sys_role where role_id=' + str(owned_rid) + ' and role_key in (' + role_keys_sql + ')']
                if statements:
                    flow.sql_exec('start transaction; ' + '; '.join(statements) + '; commit')
                check('Owned boundary accounts removed', '0', flow.scalar('select count(*) from sys_user where user_name in (' + names_sql + ')'))
                check('Owned empty role removed', '0', flow.scalar('select count(*) from sys_role where role_key=' + repr(role_key)))
                check('Owned edit-only role removed', '0', flow.scalar('select count(*) from sys_role where role_key=' + repr(editor_role_key)))
                for owned_uid in owned_ids:
                    check('Owned account bindings removed ' + str(owned_uid), '0', flow.scalar('select '
                          '(select count(*) from sys_user_role where user_id=' + str(owned_uid) + ') + '
                          '(select count(*) from sys_user_post where user_id=' + str(owned_uid) + ')'))
            except BaseException:
                errors.append('owned-account-cleanup')
        report['cleanupErrors'] = errors
        report['cleanup'] = 'fail' if errors else 'pass'
        report['cleanupScope'] = 'Only this run\'s unique accounts/role/bindings/sessions removed; ordinary system audit logs retained.'
        report['finished'] = datetime.now(timezone.utc).isoformat()
        required = ['create20-login-same-id', 'create21-rejected-no-write', 'edit21-rejected-no-write', 'edit20-reload-relogin',
                    'edit-only-cannot-reset-credentials']
        report['status'] = 'pass' if (report.get('isolationVerified') and report.get('runningBuild')
                                    and not report.get('executionFailed') and not errors and report['completed'] == required
                                    and all(row['status'] == 'pass' and row['expected'] == row['actual'] for row in report['checks'])) else 'fail'
        report['exitCode'] = 0 if report['status'] == 'pass' else 1
        (out / 'username-limits.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    return report['exitCode']


if __name__ == '__main__':
    raise SystemExit(main())
