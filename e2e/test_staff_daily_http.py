# -*- coding: utf-8 -*-
"""员工端「每日概况」接口回归（2026-10-17）：GET /app/staff/daily 走真的后端和本地验收库。

  D1 两家门店（平潭主理人、大理员工）的入住 / 在住 / 离店 / 入住率 / 新订单 / 待审退款，城市小计与合计
  D2 报名：新报名 / 待处理（待审核）/ 录用 / 到岗（已入职），岗位没填城市时归到门店城市
  D3 范围：没有授权的门店（同城）不混进来；只有线上任务专员授权的账号 403；日期格式不对 422
  D4 换一天看：第二天的入住 / 在住 / 离店跟着变；按城市筛选只留这个城市、城市选项仍是全部

前置：本地后端（KG_BASE=http://127.0.0.1:<端口>，联调模式，微信登录走 mock）+ 本地验收库（KG_DB=dkxy_acceptance_*）。
测试数据：本次运行新建的门店 / 小院 / 房型 / 订单 / 岗位 / 报名 / 授权都带 dly-<RUN> 前缀，直接写库造（离店、提前退房、
退款中这些历史状态走接口造不出来）；finally 只按本次新建的门店 ID 清理，账号是 mock 登录建的测试号（openid mock_dly-<RUN>-*）。
KG_DAILY_KEEP=<json 路径> 时不清理，把账号令牌和门店 ID 写进去（截图用），之后用 --cleanup <json 路径> 清掉。

用法：
    KG_BASE=http://127.0.0.1:8197 KG_DB=dkxy_acceptance_20261004 python -X utf8 e2e/test_staff_daily_http.py
"""
import json
import os
import re
import sys
import time
import uuid
from urllib.parse import urlparse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from test_kangaroo_flow import http, sql, scalar, sql_exec, check, PASSED, FAILED  # noqa: E402

RUN = uuid.uuid4().hex[:8]
TABLES = ['kg_application', 'kg_job', 'kg_stay_order', 'kg_stay_calendar', 'kg_stay_room_type', 'kg_stay', 'kg_staff_grant', 'kg_store']


def day(offset):
    # 后端与库按北京时间算日期
    return time.strftime('%Y-%m-%d', time.gmtime(time.time() + 8 * 3600 + offset * 86400))


def guard():
    assert urlparse(os.environ.get('KG_BASE', '')).hostname in ('localhost', '127.0.0.1'), 'local KG_BASE required'
    assert re.fullmatch(r'dkxy_acceptance_[A-Za-z0-9_]+', os.environ.get('KG_DB', '')), 'acceptance KG_DB required'


def login(tag):
    status, body = http('POST', '/app/auth/wx-login', {'code': 'dly-%s-%s' % (RUN, tag)})
    assert body.get('code') == 200, body
    return body['token'], int(body['account']['id'])


def q(v):
    return "'" + str(v).replace("\\", "\\\\").replace("'", "''") + "'"


def insert(table, row):
    """写一行，返回自增 ID（同一条连接里 last_insert_id，别的会话同时插入也不会拿错）"""
    cols = ', '.join(row.keys())
    vals = ', '.join('null' if v is None else (str(v) if isinstance(v, int) else q(v)) for v in row.values())
    rows = sql('insert into %s (%s) values (%s); select last_insert_id()' % (table, cols, vals))
    return int(rows[-1][0])


def cleanup(stores, users):
    if not stores:
        return
    ids = ','.join(str(int(s)) for s in stores)
    for t in TABLES:
        col = 'store_id'
        if t in ('kg_stay_room_type', 'kg_stay_calendar'):
            sql_exec('delete from %s where stay_id in (select stay_id from kg_stay where store_id in (%s))' % (t, ids))
            continue
        sql_exec('delete from %s where %s in (%s)' % (t, col, ids))
    if users:
        sql_exec('delete from kg_staff_grant where app_user_id in (%s)' % ','.join(str(int(u)) for u in users))
    left = sum(int(scalar('select count(*) from %s where store_id in (%s)' % (t, ids)) or 0) for t in ('kg_application', 'kg_job', 'kg_stay_order', 'kg_stay', 'kg_staff_grant', 'kg_store'))
    check('D9 清理：本次新建的门店和数据都已删除', 0, left)


def fixture():
    D = day(0)
    tag = 'dly-' + RUN
    pt = insert('kg_store', {'store_name': '平潭每日概况店-' + RUN, 'city': '平潭', 'address': '测试路', 'status': '0', 'del_flag': '0', 'remark': tag})
    dl = insert('kg_store', {'store_name': '大理每日概况店-' + RUN, 'city': '大理', 'address': '测试路', 'status': '0', 'del_flag': '0', 'remark': tag})
    other = insert('kg_store', {'store_name': '无授权店-' + RUN, 'city': '平潭', 'address': '测试路', 'status': '0', 'del_flag': '0', 'remark': tag})
    s1 = insert('kg_stay', {'store_id': pt, 'stay_name': '海边小院-' + RUN, 'city': '平潭', 'status': 'online', 'del_flag': '0', 'sort': 1})
    s2 = insert('kg_stay', {'store_id': dl, 'stay_name': '古城小院-' + RUN, 'city': '大理', 'status': 'online', 'del_flag': '0', 'sort': 1})
    s3 = insert('kg_stay', {'store_id': other, 'stay_name': '别人家小院-' + RUN, 'city': '平潭', 'status': 'online', 'del_flag': '0', 'sort': 1})
    rt = {}
    for key, stay, name, label, stock, st in [('big', s1, '大床房', '间', 6, '0'), ('bed', s1, '多人间', '床位', 4, '0'), ('off', s1, '下架房', '间', 9, '1'),
                                             ('dl', s2, '庭院房', '间', 5, '0'), ('ot', s3, '别人家', '间', 5, '0')]:
        rt[key] = insert('kg_stay_room_type', {'stay_id': stay, 'type_name': name, 'capacity': 2, 'unit_label': label, 'stock': stock,
                                               'base_price_fen': 10000, 'status': st, 'del_flag': '0'})
    insert('kg_stay_calendar', {'room_type_id': rt['bed'], 'stay_id': s1, 'stay_date': D, 'price_fen': 5000, 'stock': 2, 'occupied': 1, 'closed': '0'})
    n = [0]

    def order(stay, store, key, status, a, b, units, paid=None, out=None):
        n[0] += 1
        insert('kg_stay_order', {'order_no': 'DLY%s%02d' % (RUN, n[0]), 'stay_id': stay, 'store_id': store, 'room_type_id': rt[key], 'app_user_id': 1,
                                 'check_in': day(a), 'check_out': day(b), 'nights': b - a, 'guests': units + 1, 'units': units, 'guest_name': '测试住客',
                                 'guest_phone': '13800000000', 'room_fen': 1000, 'pay_fen': 1000, 'status': status, 'del_flag': '0',
                                 'paid_time': paid, 'checked_out_time': out, 'create_time': paid or (day(-5) + ' 10:00:00')})
    order(s1, pt, 'big', 'confirmed', 0, 2, 2, D + ' 08:30:00')          # 今日入住（待到店）、在住 2 间、新订单
    order(s1, pt, 'big', 'checked_in', -1, 1, 1, day(-3) + ' 10:00:00')  # 在住 1 间
    order(s1, pt, 'bed', 'checked_in', -2, 0, 1, day(-4) + ' 10:00:00')  # 今日离店（待退房）
    order(s1, pt, 'big', 'completed', -3, 2, 1, day(-5) + ' 10:00:00', D + ' 09:00:00')  # 今天提前退房
    order(s1, pt, 'bed', 'checked_in', -4, -1, 1, day(-6) + ' 10:00:00')  # 过了离店日没退房 → 今日离店
    order(s1, pt, 'bed', 'checked_in', 0, 1, 1, day(-1) + ' 10:00:00')   # 今日入住已到店、在住 1 床位
    order(s1, pt, 'big', 'refunding', 0, 2, 3, day(-2) + ' 10:00:00')    # 退款中：不占房
    order(s1, pt, 'big', 'refund_pending', 5, 6, 1, day(-2) + ' 10:00:00')  # 待审退款
    order(s1, pt, 'big', 'pending_payment', 0, 1, 1)                      # 待支付：不算
    order(s1, pt, 'big', 'confirmed', 1, 3, 1, D + ' 23:59:59')          # 明天入住、今天支付：新订单
    order(s2, dl, 'dl', 'checked_in', -1, 3, 2, day(-2) + ' 10:00:00')   # 大理在住 2 间
    order(s3, other, 'ot', 'checked_in', 0, 3, 5, D + ' 08:30:00')      # 没授权的门店
    j1 = insert('kg_job', {'store_id': pt, 'job_type': 'volunteer', 'title': '前台义工-' + RUN, 'city': '平潭', 'status': 'online', 'del_flag': '0'})
    j2 = insert('kg_job', {'store_id': dl, 'job_type': 'parttime', 'title': '大理兼职-' + RUN, 'city': '', 'status': 'online', 'del_flag': '0'})
    j3 = insert('kg_job', {'store_id': other, 'job_type': 'volunteer', 'title': '别人家-' + RUN, 'city': '平潭', 'status': 'online', 'del_flag': '0'})
    m = [0]

    def app(store, job, status, submit, confirm=None, arrival=None, onboard=None):
        m[0] += 1
        insert('kg_application', {'app_no': 'DLY%s%02d' % (RUN, m[0]), 'job_id': job, 'store_id': store, 'app_user_id': 900000 + m[0], 'real_name': '报名人',
                                  'phone': '13900000000', 'status': status, 'submit_time': submit, 'store_confirm_time': confirm, 'arrival_date': arrival,
                                  'onboard_time': onboard, 'del_flag': '0'})
    app(pt, j1, 'submitted', D + ' 08:00:00')
    app(pt, j1, 'submitted', day(-2) + ' 08:00:00')
    app(pt, j1, 'user_confirmed', day(-3) + ' 08:00:00')
    app(pt, j1, 'store_confirmed', day(-4) + ' 08:00:00', D + ' 11:00:00', D)
    app(pt, j1, 'onboarded', day(-9) + ' 08:00:00', day(-5) + ' 11:00:00', D, D + ' 15:00:00')
    app(pt, j1, 'withdrawn', D + ' 00:00:00')
    app(dl, j2, 'contract_signed', day(-6) + ' 08:00:00', day(-2) + ' 11:00:00', day(1))
    app(other, j3, 'submitted', D + ' 09:00:00')
    return {'D': D, 'stores': [pt, dl, other], 'pt': pt, 'dl': dl, 'other': other, 'stays': [s1, s2, s3]}


def find(rows, key, value):
    return next((r for r in rows if r.get(key) == value), None)


def main():
    guard()
    stores, users = [], []
    keep = os.environ.get('KG_DAILY_KEEP', '')
    try:
        _, uid = login('mgr')
        users.append(uid)
        f = fixture()
        stores = f['stores']
        sql_exec("insert into kg_staff_grant (app_user_id, store_id, role_code, status, del_flag, granted_by, granted_time) values "
                 "(%d, %d, 'manager', '0', '0', 'e2e', now()), (%d, %d, 'staff', '0', '0', 'e2e', now())" % (uid, f['pt'], uid, f['dl']))
        token, _ = login('mgr')  # 授权变更后重新登录，令牌里才带新门店
        _, only_uid = login('online')
        users.append(only_uid)
        sql_exec("insert into kg_staff_grant (app_user_id, store_id, role_code, status, del_flag, granted_by, granted_time) values "
                 "(%d, %d, 'online_staff', '0', '0', 'e2e', now())" % (only_uid, f['pt']))
        only_token, _ = login('online')

        status, body = http('GET', '/app/staff/daily', app_token=token)
        check('D1 接口可用', 200, body.get('code'))
        d = body.get('data') or {}
        cities = d.get('cities') or []
        pt = find(cities, 'city', '平潭') or {}
        dl = find(cities, 'city', '大理') or {}
        st = find(pt.get('stays') or [], 'stayId', f['stays'][0]) or {}
        check('D1 海边小院 今日入住 / 已到店', [2, 1], [st.get('arrivals'), st.get('arrived')])
        check('D1 海边小院 在住单数 / 间数 / 当天库存 / 入住率', [3, 4, 8, '50%'], [st.get('inHouse'), st.get('units'), st.get('stock'), st.get('occupancyText')])
        check('D1 海边小院 今日离店 / 已退房（含提前退房、逾期未退）', [3, 1], [st.get('departures'), st.get('departed')])
        check('D1 海边小院 新订单 / 待审退款', [2, 1], [st.get('newOrders'), st.get('refundPending')])
        ds = find(dl.get('stays') or [], 'stayId', f['stays'][1]) or {}
        check('D1 古城小院 在住 / 入住率', [1, '40%'], [ds.get('inHouse'), ds.get('occupancyText')])
        check('D3 没授权的门店（同城）不出现', None, find(pt.get('stays') or [], 'stayId', f['stays'][2]))
        pa = find(pt.get('apps') or [], 'storeId', f['pt']) or {}
        check('D2 平潭报名 新报名 / 待处理 / 待审核 / 录用 / 到岗 / 已入职', [2, 3, 2, 1, 2, 1],
              [pa.get('newApps'), pa.get('pending'), pa.get('toReview'), pa.get('hired'), pa.get('arriving'), pa.get('onboarded')])
        da = find(dl.get('apps') or [], 'storeId', f['dl']) or {}
        check('D2 岗位没填城市归到门店城市（大理），待处理 1', 1, da.get('pending'))
        check('D3 没授权门店的报名不出现', None, find(pt.get('apps') or [], 'storeId', f['other']))
        # 合计只看自己授权的两家门店：库存 8 + 5、在住间数 4 + 2（账号若还有别的授权会更大，这里是新号）
        total = d.get('total') or {}
        check('D1 合计 在住 / 间数 / 库存 / 入住率', [4, 6, 13, '46.2%'], [total.get('inHouse'), total.get('units'), total.get('stock'), total.get('occupancyText')])
        check('D1 更新时间 HH:mm', True, bool(re.fullmatch(r'\d\d:\d\d', str(d.get('updatedAt') or ''))))

        status, body = http('GET', '/app/staff/daily', app_token=only_token)
        check('D3 只有线上任务专员授权 → 403', 403, body.get('code'))
        status, body = http('GET', '/app/staff/daily?date=2026/10/17', app_token=token)
        check('D3 日期格式不对 → 422', 422, body.get('code'))
        status, body = http('GET', '/app/staff/daily', app_token=None)
        check('D3 没登录 → 401', 401, body.get('code'))

        status, body = http('GET', '/app/staff/daily?date=%s&city=%s' % (day(1), '%E5%B9%B3%E6%BD%AD'), app_token=token)
        d2 = body.get('data') or {}
        c2 = d2.get('cities') or []
        check('D4 按城市筛选只留平潭', ['平潭'], [c.get('city') for c in c2])
        check('D4 城市选项仍有大理', True, '大理' in (d2.get('cityOptions') or []))
        st2 = find((c2[0] if c2 else {}).get('stays') or [], 'stayId', f['stays'][0]) or {}
        check('D4 第二天 入住 / 在住 / 间数 / 离店 / 库存', [1, 2, 3, 2, 10], [st2.get('arrivals'), st2.get('inHouse'), st2.get('units'), st2.get('departures'), st2.get('stock')])
        if keep:
            with open(keep, 'w', encoding='utf-8') as fp:
                json.dump({'token': token, 'stores': stores, 'users': users, 'stays': f['stays'], 'run': RUN}, fp, ensure_ascii=False)
            print('fixture kept ->', keep)
    finally:
        if not keep:
            cleanup(stores, users)
    print('PASSED %d FAILED %d' % (len(PASSED), len(FAILED)))
    sys.exit(1 if FAILED else 0)


if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] == '--cleanup':
        guard()
        info = json.load(open(sys.argv[2], encoding='utf-8'))
        cleanup(info['stores'], info['users'])
        print('PASSED %d FAILED %d' % (len(PASSED), len(FAILED)))
    else:
        main()
