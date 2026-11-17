"""Real local HTTP/MySQL concurrency + failure injection, mock WeChat only.
Creates isolated users and narrowly scoped temporary DB triggers, always cleaned.
Never run concurrently with another DB-mutating acceptance suite.
"""
import concurrent.futures
import json
import os
import threading
import time
import uuid
from urllib.parse import urlparse
from test_kangaroo_flow import http, scalar, sql_exec, BASE


def main():
    assert urlparse(BASE).hostname in ('localhost', '127.0.0.1')
    expected_db = os.environ.get('KG_DB', '')
    assert expected_db.startswith(('dkxy_acceptance_', 'kg_acceptance_', 'acceptance_')), 'Explicit isolated KG_DB required'
    assert scalar('select database()') == expected_db, 'Actual database must match isolated KG_DB'
    assert http('GET', '/app/auth/ping')[1]['data']['wxMock'] is True
    tag = uuid.uuid4().hex
    ids, tokens, checks = [], [], []
    failure_trigger = 'kg_test_phone_fail_' + tag[:12]
    race_trigger = 'kg_test_phone_race_' + tag[:12]
    target = '137' + str(time.time_ns())[-8:]
    old = '136' + str(time.time_ns())[-8:]
    race_phone = '135' + str(time.time_ns())[-8:]
    def user(n):
        body = http('POST', '/app/auth/wx-login', {'code': 'phone-consistency-' + tag + str(n)})[1]
        assert body.get('code') == 200 and body.get('mockLogin') is True
        uid, token = int(body['account']['id']), body['token']
        ids.append(uid); tokens.append(token)
        return uid, token
    def check(name, ok):
        checks.append({'name': name, 'passed': bool(ok)})
        print(('PASS ' if ok else 'FAIL ') + name, flush=True)
    try:
        a, ta = user('claim')
        b, tb = user('owner')
        assert http('POST', '/app/auth/bind-phone', {'phone': target}, app_token=ta)[1]['code'] == 200
        assert http('POST', '/app/auth/phone-verify', {'code': 'mock-phone-' + old}, app_token=tb)[1]['code'] == 200
        # Narrow fault: fail only when this newly created account receives target.
        sql_exec("create trigger %s before update on kg_app_user for each row "
                 "set NEW.phone_verified_time = IF(NEW.user_id=%d and NEW.phone='%s', "
                 "cast('PHONE_TEST_INVALID_DATE' as datetime), NEW.phone_verified_time)" % (failure_trigger, b, target))
        result = http('POST', '/app/auth/phone-verify', {'code': 'mock-phone-' + target}, app_token=tb)[1]
        check('injected database failure is reported as failure', result.get('code') != 200)
        check('failed takeover restores the previous unverified owner', scalar("select phone='%s' from kg_app_user where user_id=%d" % (target, a)) == '1')
        check('failed replacement preserves old verified phone', scalar("select phone='%s' and phone_verified_time is not null from kg_app_user where user_id=%d" % (old, b)) == '1')
        sql_exec('drop trigger ' + failure_trigger)
        result = http('POST', '/app/auth/phone-verify', {'code': 'mock-phone-' + target}, app_token=tb)[1]
        check('fresh authorization can complete after rollback', result.get('code') == 200)
        check('successful takeover clears only the unverified claimant', scalar('select phone is null and phone_verified_time is null from kg_app_user where user_id=%d' % a) == '1')
        check('successful takeover saves phone and verification together', scalar("select phone='%s' and phone_verified_time is not null from kg_app_user where user_id=%d" % (target, b)) == '1')

        racers = [user('race'+str(i)) for i in range(8)]
        # Expose the old read-before-write race without changing business results.
        sql_exec("create trigger %s before update on kg_app_user for each row "
                 "set @kg_phone_test_delay = IF(NEW.phone='%s', sleep(0.15), 0)" % (race_trigger, race_phone))
        barrier = threading.Barrier(len(racers))
        def bind(racer):
            barrier.wait()
            return http('POST', '/app/auth/phone-verify', {'code': 'mock-phone-' + race_phone}, app_token=racer[1])[1].get('code')
        with concurrent.futures.ThreadPoolExecutor(max_workers=len(racers)) as pool:
            codes = list(pool.map(bind, racers))
        check('concurrent same-phone claims have exactly one successful owner', codes.count(200) == 1)
        check('concurrent conflicting claims return business conflict', all(c in (200, 409) for c in codes))
        check('database retains exactly one active verified owner', scalar("select count(*) from kg_app_user where phone='%s' and phone_verified_time is not null and del_flag='0'" % race_phone) == '1')
        sql_exec('drop trigger ' + race_trigger)
    finally:
        sql_exec('drop trigger if exists ' + failure_trigger)
        sql_exec('drop trigger if exists ' + race_trigger)
        for token in tokens:
            http('POST', '/app/auth/logout', app_token=token)
        for uid in ids:
            sql_exec("update kg_app_user set phone=null,phone_verified_time=null,del_flag='2' where user_id=%d" % uid)
    print(json.dumps({'scope':'real local HTTP/MySQL; WeChat mock; fault injection and concurrency',
                      'checks':checks,'passed':sum(c['passed'] for c in checks),'total':len(checks)}, ensure_ascii=False, indent=2))
    assert len(checks) == 9 and all(c['passed'] for c in checks), 'Phone consistency regression failed'


if __name__ == '__main__':
    main()
