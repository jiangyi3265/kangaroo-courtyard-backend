"""Phone binding persistence/auth checks against localhost mock WeChat + real isolated DB.

Not a real WeChat phone authorization test. Never run against production.
Creates two tagged app users, revokes test tokens, soft-deletes only these users.
"""
import json
import os
import time
import uuid
from urllib.parse import urlparse
from test_kangaroo_flow import http, scalar, sql_exec, BASE


def main():
    assert urlparse(BASE).hostname in ('127.0.0.1', 'localhost'), 'Local API required'
    assert http('GET', '/app/auth/ping')[1].get('data', {}).get('wxMock') is True
    expected_db = os.environ.get('KG_DB', '')
    assert expected_db.startswith(('dkxy_acceptance_', 'kg_acceptance_', 'acceptance_')), 'Explicit isolated KG_DB required'
    assert scalar('select database()') == expected_db, 'Actual database must match isolated KG_DB'
    checks, users, tokens = [], [], []
    tag = 'phone-bind-' + uuid.uuid4().hex
    phone = '138' + str(time.time_ns())[-8:]
    def check(name, condition):
        assert condition, name
        checks.append(name)
    def login(suffix):
        body = http('POST', '/app/auth/wx-login', {'code': tag + suffix})[1]
        assert body.get('code') == 200 and body.get('mockLogin') is True
        token, uid = body['token'], int(body['account']['id'])
        tokens.append(token)
        if uid not in users:
            users.append(uid)
        return token, uid
    try:
        tok, uid = login('-owner')
        rejected = http('POST', '/app/auth/phone-verify', {'code': 'mock-phone-' + phone})[1]
        check('unauthenticated binding rejected', rejected.get('code') != 200)
        rejected = http('POST', '/app/auth/phone-verify', {'code': 'invalid'}, app_token=tok)[1]
        check('invalid phone authorization rejected', rejected.get('code') != 200)
        check('invalid authorization did not save a phone', scalar('select phone is null from kg_app_user where user_id=%d' % uid) == '1')
        result = http('POST', '/app/auth/phone-verify', {'code': 'mock-phone-' + phone}, app_token=tok)[1]
        check('authorized phone binding confirmed', result.get('code') == 200 and result['data']['phoneBound'] is True)
        check('account identity preserved', int(result['data']['id']) == uid)
        check('full phone is not exposed', result['data']['phone'] != phone and '****' in result['data']['phone'])
        check('phone stored in database', scalar('select phone from kg_app_user where user_id=%d' % uid) == phone)
        check('verification time stored in database', scalar('select phone_verified_time is not null from kg_app_user where user_id=%d' % uid) == '1')
        other_tok, other_uid = login('-other')
        rejected = http('POST', '/app/auth/phone-verify', {'code': 'mock-phone-' + phone}, app_token=other_tok)[1]
        check('another verified owner cannot be taken over', rejected.get('code') != 200)
        check('conflict does not bind the second account', scalar('select phone is null from kg_app_user where user_id=%d' % other_uid) == '1')
        http('POST', '/app/auth/logout', app_token=tok)
        tok2, uid2 = login('-owner')
        profile = http('GET', '/app/me/profile', app_token=tok2)[1]
        check('new login restores the same identity', uid2 == uid)
        check('verified binding survives logout and login', profile.get('code') == 200 and profile['data']['phoneVerified'] is True and profile['data']['phone'] == result['data']['phone'])
    finally:
        for token in tokens:
            http('POST', '/app/auth/logout', app_token=token)
        for uid in users:
            # Only identities created by this run are cleaned up; never reset demo data.
            sql_exec("update kg_app_user set del_flag='2',phone=null,phone_verified_time=null where user_id=%d" % uid)
        check('test accounts cleaned up', all(scalar("select del_flag from kg_app_user where user_id=%d" % uid) == '2' for uid in users))
    print(json.dumps({'scope': 'local mock WeChat, real HTTP and MySQL persistence', 'passed': len(checks), 'checks': checks}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
