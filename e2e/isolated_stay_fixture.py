"""Local-only, per-run stays/services for C24/C27; never borrow another suite's rows.

Business setup uses real HTTP APIs, including staff submission and admin review.
Cleanup only follows IDs created by this fixture (and explicitly registered test
accounts), runs in finally, and verifies every cleanup predicate is empty.
"""
import json
import os
from pathlib import Path
import re
import sys
import time
from urllib.parse import urlparse
import uuid

import test_kangaroo_flow as flow


def id_list(ids):
    return ','.join(str(int(i)) for i in sorted(set(ids)))


def cleanup_predicate(columns, scopes):
    """No unscoped DELETE; only globally unique entity keys, never generic id."""
    return ' or '.join('`%s` in (%s)' % (key, id_list(ids))
                       for key, ids in scopes.items() if ids and key in columns)


class IsolatedStayFixture:
    def __init__(self, label):
        self.prefix = 'stay-fixture-' + re.sub(r'[^a-zA-Z0-9-]', '', label) + '-' + uuid.uuid4().hex[:8]
        self.stores, self.users, self.tokens = [], set(), set()
        self.stay_id = self.room_type_id = self.service_id = self.job_id = None

    def user(self, token, uid):
        self.tokens.add(token)
        self.users.add(int(uid))

    def api(self, method, path, body=None, app=None):
        status, result = flow.http(method, path, body, token=None if app else self.admin, app_token=app)
        assert status == 200 and result.get('code') == 200, (path, result)
        return result.get('data')

    def __enter__(self):
        assert urlparse(os.environ.get('KG_BASE', '')).hostname in ('localhost', '127.0.0.1', '::1'), 'Explicit local KG_BASE required'
        assert re.fullmatch(r'(?:dkxy_acceptance_|kg_acceptance_|acceptance_)[A-Za-z0-9_]+', os.environ.get('KG_DB', '')), 'Explicit isolated KG_DB required'
        sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
        import verify
        verify.assert_isolated_backend()
        self.admin = flow.admin_login()
        try:
            self.prepare()
            return self
        except BaseException:
            self.cleanup()
            raise

    def __exit__(self, exc_type, exc, tb):
        self.cleanup()
        return False

    def prepare(self):
        from test_ext_manage import upload
        for suffix in ('A', 'B'):
            sid = self.api('POST', '/kangaroo/store', {
                'storeName': self.prefix + '-' + suffix, 'city': '大理',
                'address': '本地隔离验收地址', 'contactPhone': '18500000000',
                'cover': '/static/media/rooms-cover-1.jpg', 'intro': '仅本地验收测试门店'})
            self.stores.append(int(sid))
        self.store_id, self.other_store_id = self.stores
        code = self.prefix + '-manager'
        _, login = flow.http('POST', '/app/auth/wx-login', {'code': code})
        assert login.get('code') == 200, login
        self.user(login['token'], login['account']['id'])
        self.api('POST', '/kangaroo/grant', {'appUserId': int(login['account']['id']), 'storeId': self.store_id, 'roleCode': 'manager'})
        _, login = flow.http('POST', '/app/auth/wx-login', {'code': code})
        assert login.get('code') == 200, login
        manager = login['token']
        self.user(manager, login['account']['id'])
        self.api('POST', '/app/staff/stay/stays?storeId=%s' % self.store_id, {
            'stayName': self.prefix, 'address': '本地隔离验收地址', 'areaDesc': '本地验收',
            'cover': '/static/media/rooms-cover-1.jpg'}, app=manager)
        self.stay_id = int(flow.scalar('select stay_id from kg_stay where store_id=%s' % self.store_id))
        self.api('POST', '/app/staff/stay/stays/%s/room-types' % self.stay_id, {
            'typeName': '独立验收大床房', 'roomKind': 'private', 'capacity': 2, 'stock': 3, 'basePriceFen': 20000}, app=manager)
        self.room_type_id = int(flow.scalar('select room_type_id from kg_stay_room_type where stay_id=%s' % self.stay_id))
        expire = time.strftime('%Y-%m-%d', time.gmtime(time.time() + 366 * 86400))
        for qt in ('license', 'fire', 'idcard'):
            result = upload('/app/staff/stay/stays/%s/qualifications?type=%s&expire=%s' % (self.stay_id, qt, expire),
                            manager, self.prefix + '-' + qt + '.pdf', b'%PDF-1.4 isolated local fixture')
            assert result.get('code') == 200, result
        self.api('POST', '/app/staff/stay/stays/%s/submit' % self.stay_id, {}, app=manager)
        self.api('PUT', '/kangaroo/stayops/stay/%s/approve' % self.stay_id)
        assert flow.scalar('select status from kg_stay where stay_id=%s' % self.stay_id) == 'online'
        cat = flow.scalar("select dict_value from sys_dict_data where dict_type='kg_shop_category' and status='0' order by dict_sort limit 1")
        self.api('POST', '/kangaroo/ext/shopservice', {
            'storeId': self.store_id, 'category': cat, 'serviceName': self.prefix,
            'priceFen': 8800, 'unitLabel': '份', 'timeSlots': '10:00-11:00,14:00-15:00',
            'slotCapacity': 5, 'advanceHours': 0, 'rescheduleLimit': 1, 'refundable': '1', 'status': '0'})
        self.service_id = int(flow.scalar('select service_id from kg_shop_service where store_id=%s' % self.store_id))
        self.job_id = int(self.api('POST', '/kangaroo/job', {
            'storeId': self.store_id, 'jobType': 'volunteer', 'title': self.prefix,
            'city': '大理', 'address': '本地隔离验收地址', 'salaryType': 'daily', 'salaryMin': 100,
            'salaryUnit': '元/天', 'minDays': 1, 'dailyHours': '每天4小时', 'boardType': 'all',
            'insuranceFlag': '0', 'headcount': 3, 'tags': '咖啡', 'content': '本地验收', 'requirement': '仅测试'}))
        self.api('PUT', '/kangaroo/job/changeStatus', {'jobId': self.job_id, 'status': 'published'})
        print('Independent stay fixture: ' + json.dumps({
            'prefix': self.prefix, 'storeId': self.store_id, 'otherStoreId': self.other_store_id,
            'stayId': self.stay_id, 'roomTypeId': self.room_type_id, 'serviceId': self.service_id}, ensure_ascii=False))

    def cleanup(self):
        for token in self.tokens:
            status, result = flow.http('POST', '/app/auth/logout', app_token=token)
            assert status in (200, 401) and result.get('code') in (200, 401), 'Fixture session cleanup failed'
        scopes = {'store_id': self.stores}
        # Account keys are exact IDs returned by this run's real mock-identity API.
        for key in ('app_user_id', 'user_id', 'recipient_id', 'sender_id', 'viewer_id', 'target_id',
                    'follower_id', 'followee_id', 'blocked_id', 'reporter_id', 'from_user_id', 'payee_user_id'):
            scopes[key] = self.users
        schema = {r[0]: set(r[1].split(',')) for r in flow.sql(
            "select table_name,group_concat(column_name) from information_schema.columns "
            "where table_schema=database() and table_name like 'kg\\_%' group by table_name")}
        # Capture dependent identifiers before removing any parent. Generic order_id
        # and report_id are deliberately table-specific: their sequences can overlap.
        entities = [('kg_job', 'job_id'), ('kg_application', 'app_id'), ('kg_stay', 'stay_id'),
                    ('kg_stay_room_type', 'room_type_id'), ('kg_shop_service', 'service_id'),
                    ('kg_activity', 'activity_id'), ('kg_chat_conv', 'conv_id'), ('kg_task', 'task_id'),
                    ('kg_post', 'post_id')]
        for table, key in entities:
            predicate = cleanup_predicate(schema[table], scopes)
            scopes[key] = [int(r[0]) for r in flow.sql('select `%s` from `%s` where %s' % (key, table, predicate))] if predicate else []
        predicates = {}
        for table, columns in schema.items():
            assert re.fullmatch(r'kg_[a-z0-9_]+', table), 'Unexpected identifier'
            scoped = dict(scopes)
            # target_id in favorites/reviews/reports identifies business objects,
            # not people; never conflate those numeric IDs with account IDs.
            if table != 'kg_pii_access':
                scoped.pop('target_id', None)
            pred = cleanup_predicate(columns, scoped)
            if pred:
                predicates[table] = pred
        # Subscription and comment children carry neither user/store nor an entity
        # key above. Select their parent IDs while those parents still exist.
        for parent, key, child in [('kg_message', 'msg_id', 'kg_subscribe_outbox'),
                                   ('kg_post_comment', 'comment_id', 'kg_post_comment_like'),
                                   ('kg_weekly_report', 'report_id', 'kg_weekly_item'),
                                   ('kg_stay_order', 'order_id', 'kg_stay_activity_selection')]:
            if parent in predicates:
                ids = [int(r[0]) for r in flow.sql('select `%s` from `%s` where %s' % (key, parent, predicates[parent]))]
                if ids:
                    predicates[child] = ('(' + predicates[child] + ') or ' if child in predicates else '') + '`%s` in (%s)' % (key, id_list(ids))
        # Every tested idempotency key contains the original suite's run marker.
        # Never truncate the shared ledger.
        marker = self.prefix.split('-')[-2]
        assert re.fullmatch(r'[a-zA-Z0-9]+', marker)
        predicates['kg_idempotency'] = "idem_key like '%" + marker + "%'"
        # Store/account parents last; all predicates are fixed before transaction.
        order = sorted(predicates, key=lambda t: (t in ('kg_store', 'kg_app_user'), t))
        if order:
            flow.sql_exec('start transaction; ' + '; '.join('delete from `%s` where %s' % (t, predicates[t]) for t in order) + '; commit')
            remaining = [(t, flow.scalar('select count(*) from `%s` where %s' % (t, predicates[t]))) for t in order]
            assert not [(t, n) for t, n in remaining if n != '0'], ('Fixture cleanup incomplete', remaining)
        print('Independent stay fixture cleanup: PASS (' + self.prefix + ')')
