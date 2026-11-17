# -*- coding: utf-8 -*-
"""通过管理后台接口灌入一套可演示的业务数据。

全部走真实的后台 API（含权限校验），不直接写数据库，
因此这个脚本同时也是"管理后台能真正创建业务数据"的证据。

用法: python e2e/seed_demo_data.py [--reset]
  --reset 先把之前由本脚本创建的演示门店与岗位关闭（不物理删除，避免误伤真实数据）
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from redis_client import MiniRedis  # noqa: E402

BASE = os.environ.get('KG_BASE', 'http://localhost:8080')
REDIS_DB = int(os.environ.get('KG_REDIS_DB', '9'))


def http(method, path, body=None, token=None):
    url = BASE + path
    data = json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None
    headers = {'Content-Type': 'application/json;charset=UTF-8'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.loads(r.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode('utf-8', 'replace'))


def admin_login():
    body = http('GET', '/captchaImage')
    uuid_ = body['uuid']
    code = ''
    if body.get('captchaEnabled', True):
        r = MiniRedis(db=REDIS_DB)
        try:
            raw = r.get('captcha_codes:' + uuid_)
        finally:
            r.close()
        code = raw.decode('utf-8').strip().strip('"')
    res = http('POST', '/login', {'username': 'admin', 'password': 'admin123', 'code': code, 'uuid': uuid_})
    assert res.get('code') == 200, res
    return res['token']


STORES = [
    {'storeName': '古城拾光咖啡馆', 'city': '大理', 'address': '大理古城 · 人民路',
     'cover': '/static/media/jobs-vol-1.jpg', 'intro': '一间开在古城巷子里的手冲咖啡馆，常年招募驻店伙伴。',
     'contactPhone': '18512345678'},
    {'storeName': '双廊听海民宿', 'city': '大理', 'address': '双廊古镇 · 环海路',
     'cover': '/static/media/jobs-vol-2.jpg', 'intro': '洱海边的小院民宿，前台与客房都需要帮手。',
     'contactPhone': '18512345679'},
    {'storeName': '喜洲花间手作', 'city': '大理', 'address': '喜洲古镇 · 四方街',
     'cover': '/static/media/jobs-vol-3.jpg', 'intro': '扎染与花艺体验空间。',
     'contactPhone': '18512345680'},
]

JOBS = [
    # 义工换宿
    {'store': 0, 'jobType': 'volunteer', 'title': '古城咖啡馆驻店伙伴',
     'cover': '/static/media/jobs-vol-1.jpg', 'address': '大理古城', 'city': '云南大理',
     'salaryType': 'daily', 'salaryMin': 120, 'salaryUnit': '元/天', 'settleDesc': '月结 · 次月5日前发放',
     'minDays': 14, 'dailyHours': '每日4-6小时', 'shiftDesc': '轮班排休 · 具体见排班表',
     'boardType': 'all', 'boardDesc': '包吃包住（独立宿舍 · Wi-Fi · 洗衣机）',
     'insuranceFlag': '0', 'headcount': 3, 'tags': '咖啡制作,店铺运营,接待顾客',
     'content': '协助日常出品、店面整理与顾客接待。', 'requirement': '性格开朗，能连续驻店两周以上。'},
    {'store': 1, 'jobType': 'volunteer', 'title': '双廊民宿前台助手',
     'cover': '/static/media/jobs-vol-2.jpg', 'address': '双廊古镇', 'city': '云南大理',
     'salaryType': 'daily', 'salaryMin': 110, 'salaryUnit': '元/天', 'settleDesc': '月结',
     'minDays': 14, 'dailyHours': '每日4-6小时', 'boardType': 'all',
     'insuranceFlag': '0', 'headcount': 2, 'tags': '前台接待,客房协助,活动协助',
     'content': '入住登记、客房协助与活动配合。', 'requirement': '有服务意识，普通话流利。'},
    {'store': 2, 'jobType': 'volunteer', 'title': '洱海边花艺帮手',
     'cover': '/static/media/jobs-vol-3.jpg', 'address': '喜洲古镇', 'city': '云南大理',
     'salaryType': 'daily', 'salaryMin': 100, 'salaryUnit': '元/天', 'settleDesc': '月结',
     'minDays': 7, 'dailyHours': '每日4-6小时', 'boardType': 'all',
     'insuranceFlag': '0', 'headcount': 2, 'tags': '花艺协助,活动布置,拍照记录',
     'content': '花材整理、活动布置与内容拍摄。', 'requirement': '对手作有兴趣。'},
    # 兼职旅行
    {'store': 0, 'jobType': 'parttime', 'title': '咖啡店周末兼职店员',
     'cover': '/static/media/jobs-pt-1.jpg', 'address': '大理古城', 'city': '云南大理',
     'salaryType': 'daily', 'salaryMin': 180, 'salaryUnit': '元/天', 'settleDesc': '周结',
     'shiftDesc': '周六-周日', 'shiftTime': '10:00-18:00', 'boardType': 'room',
     'headcount': 2, 'tags': '咖啡制作,收银接待,清洁整理',
     'content': '周末出品与收银。', 'requirement': '可稳定出勤。'},
    {'store': 1, 'jobType': 'parttime', 'title': '旅拍摄影助理',
     'cover': '/static/media/jobs-pt-2.jpg', 'address': '洱海生态廊道', 'city': '云南大理',
     'salaryType': 'daily', 'salaryMin': 220, 'salaryUnit': '元/天', 'settleDesc': '次日结',
     'shiftDesc': '周五-周日', 'shiftTime': '08:00-17:00', 'boardType': 'room',
     'headcount': 1, 'tags': '摄影助理,道具整理,客户引导',
     'content': '跟拍协助与器材管理。', 'requirement': '能吃苦，会基础构图。'},
    {'store': 2, 'jobType': 'parttime', 'title': '手作体验店店员',
     'cover': '/static/media/jobs-pt-3.jpg', 'address': '喜洲古镇', 'city': '云南大理',
     'salaryType': 'daily', 'salaryMin': 160, 'salaryUnit': '元/天', 'settleDesc': '周结',
     'shiftDesc': '周末排班', 'shiftTime': '10:00-18:00', 'boardType': 'room',
     'headcount': 2, 'tags': '手作教学,客户接待,材料整理',
     'content': '带体验课与材料准备。', 'requirement': '有手作基础优先。'},
    # 全职旅行
    {'store': 1, 'jobType': 'fulltime', 'title': '民宿运营店长',
     'cover': '/static/media/jobs-ft-1.jpg', 'address': '双廊古镇', 'city': '云南大理',
     'salaryType': 'monthly', 'salaryMin': 6000, 'salaryMax': 8000, 'salaryUnit': '元/月',
     'settleDesc': '月结', 'trainDesc': '运营管理培训', 'probation': '1-2个月',
     'benefits': '包住（单间）,五险一金,带薪年假', 'boardType': 'room',
     'headcount': 1, 'tags': '店铺运营,团队管理,客诉处理',
     'content': '负责门店整体运营。', 'requirement': '两年以上相关经验。'},
    {'store': 0, 'jobType': 'fulltime', 'title': '咖啡品牌主理人',
     'cover': '/static/media/jobs-ft-2.jpg', 'address': '大理古城', 'city': '云南大理',
     'salaryType': 'monthly', 'salaryMin': 7000, 'salaryMax': 12000, 'salaryUnit': '元/月',
     'settleDesc': '月结', 'trainDesc': '咖啡师认证培训', 'probation': '1-3个月',
     'benefits': '包住（单间）,五险一金,带薪年假', 'boardType': 'room',
     'headcount': 1, 'tags': '咖啡研发,团队管理,品牌策划',
     'content': '产品研发与品牌运营。', 'requirement': '有主理经验。'},
    {'store': 2, 'jobType': 'fulltime', 'title': '内容策划专员',
     'cover': '/static/media/jobs-ft-3.jpg', 'address': '大理市区', 'city': '云南大理',
     'salaryType': 'monthly', 'salaryMin': 5000, 'salaryMax': 7000, 'salaryUnit': '元/月',
     'settleDesc': '月结', 'trainDesc': '内容策划培训', 'probation': '1个月',
     'benefits': '包住（可选）,五险一金,带薪年假', 'boardType': 'room',
     'headcount': 1, 'tags': '内容策划,活动执行,数据分析',
     'content': '负责内容选题与活动执行。', 'requirement': '文字功底扎实。'},
    # 在线兼职
    # 线上任务走 /kangaroo/online-task 建（佣金、截止时间、验收标准在 kg_online_task）；下面的 taskType / deliverables / criteria 给它用
    {'store': 0, 'jobType': 'online', 'title': '小红书图文笔记撰写', 'taskType': '内容写作',
     'deliverables': '1 篇原创图文笔记（含 6 张配图）', 'criteria': '原创、配图清晰、按选题撰写',
     'cover': '/static/media/jobs-ol-1.jpg', 'address': '远程', 'city': '不限',
     'salaryType': 'piece', 'salaryMin': 80, 'salaryMax': 150, 'salaryUnit': '元/篇',
     'settleDesc': '按篇结算', 'deliverDesc': '2天内', 'estHours': '2-3小时',
     'remoteFlag': '0', 'publisherName': '兴乐媛', 'boardType': 'none',
     'headcount': 5, 'tags': '文案撰写,小红书,生活方式',
     'content': '按选题撰写图文笔记。', 'requirement': '有小红书运营经验优先。'},
    {'store': 1, 'jobType': 'online', 'title': '短视频剪辑（旅行类）', 'taskType': '视频剪辑',
     'deliverables': '1 条 60 秒内成片', 'criteria': '画面流畅、调色统一、无水印',
     'cover': '/static/media/jobs-ol-2.jpg', 'address': '远程', 'city': '不限',
     'salaryType': 'piece', 'salaryMin': 150, 'salaryMax': 300, 'salaryUnit': '元/条',
     'settleDesc': '按条结算', 'deliverDesc': '3天内', 'estHours': '3-5小时',
     'remoteFlag': '0', 'publisherName': '兴乐媛', 'boardType': 'none',
     'headcount': 3, 'tags': '视频剪辑,剪映,旅行',
     'content': '素材剪辑与调色。', 'requirement': '熟练使用剪映。'},
    {'store': 2, 'jobType': 'online', 'title': '公众号文章排版', 'taskType': '图片设计',
     'deliverables': '1 篇排好版的公众号文章', 'criteria': '版式整洁、配图与正文一致',
     'cover': '/static/media/jobs-ol-3.jpg', 'address': '远程', 'city': '不限',
     'salaryType': 'piece', 'salaryMin': 60, 'salaryMax': 100, 'salaryUnit': '元/篇',
     'settleDesc': '按篇结算', 'deliverDesc': '1天内', 'estHours': '1-2小时',
     'remoteFlag': '0', 'publisherName': '润美媛', 'boardType': 'none',
     'headcount': 3, 'tags': '排版设计,公众号,图文编辑',
     'content': '文章排版与配图。', 'requirement': '审美在线。'},
]


APPLICANTS = [
    # (登录code后缀, 昵称, 姓名, 年龄, 手机号, 岗位标题, 技能, 目标状态)
    ('app-01', '晨光', '陈晨光', 25, '13800138001', '古城咖啡馆驻店伙伴', '咖啡制作,拉花,手冲,沟通协作', 'submitted'),
    ('app-02', '暖暖', '沈暖暖', 23, '13800138002', '咖啡店周末兼职店员', '收银,接待,清洁', 'interview_booked'),
    ('app-03', '风筝', '林风筝', 27, '13800138003', '小红书图文笔记撰写', '文案撰写,选题策划', 'review_passed'),
    ('app-04', '星辰', '赵星辰', 29, '13800138004', '咖啡品牌主理人', '门店运营,团队管理', 'review_rejected'),
    ('app-05', '小雨', '吴小雨', 22, '13800138005', '咖啡店周末兼职店员', '咖啡制作', 'submitted'),
    ('app-06', '阿宁', '周宁', 24, '13800138006', '小红书图文笔记撰写', '排版,配图', 'submitted'),
]


def app_http(method, path, body=None, app_token=None):
    url = BASE + path
    data = json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None
    headers = {'Content-Type': 'application/json;charset=UTF-8'}
    if app_token:
        headers['X-App-Token'] = app_token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.loads(r.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode('utf-8', 'replace'))


def seed_applications(token, store_ids):
    """灌入几条处于不同状态的演示报名，让管理后台与员工端有真实数据可看。

    全部走真实接口：小程序登录 -> 提交报名 -> 后台审核 -> 预约面试。
    需要后端以 -Dkangaroo.wx.mock-enabled=true 启动（本地联调模式）。
    """
    ping = app_http('GET', '/app/auth/ping')
    if not (ping.get('data') or {}).get('wxMock'):
        print('后端未开启本地联调模式，跳过演示报名灌入（真实微信登录无法脚本化）')
        return

    jobs = http('GET', '/kangaroo/job/list?pageSize=200', token=token)
    job_by_title = {r['title']: r for r in jobs.get('rows', [])}

    existing = http('GET', '/kangaroo/application/list?pageSize=200', token=token)
    existing_phones = set(r.get('phone') for r in existing.get('rows', []))

    created = 0
    for suffix, nickname, real_name, age, phone, job_title, skills, target in APPLICANTS:
        if phone in existing_phones:
            continue
        job = job_by_title.get(job_title)
        if not job:
            continue
        login = app_http('POST', '/app/auth/wx-login', {'code': 'h5-seed-' + suffix, 'nickname': nickname})
        if login.get('code') != 200:
            print('  演示账号登录失败:', nickname, login.get('msg'))
            continue
        app_token = login['token']
        arrival = time.strftime('%Y-%m-%d', time.localtime(time.time() + 86400 * 3))
        res = app_http('POST', '/app/application', {
            'jobId': job['jobId'], 'name': real_name, 'age': age, 'phone': phone,
            'skills': skills, 'arrival': arrival,
            'days': max(job.get('minDays') or 1, 1), 'note': '',
            'idemKey': 'seed-' + suffix
        }, app_token=app_token)
        if res.get('code') != 200:
            print('  演示报名失败:', nickname, res.get('msg'))
            continue
        app_id = res['data']['appId']
        created += 1

        if target in ('review_passed', 'interview_booked'):
            http('PUT', '/kangaroo/application/audit', {'appId': app_id, 'pass': True}, token=token)
        elif target == 'review_rejected':
            http('PUT', '/kangaroo/application/audit',
                 {'appId': app_id, 'pass': False, 'reason': '到岗日期与门店排期不匹配'}, token=token)

        if target == 'interview_booked':
            slots = app_http('GET', '/app/application/%s/slots' % app_id, app_token=app_token)
            options = slots.get('data') or []
            if options:
                app_http('POST', '/app/application/%s/book-interview' % app_id,
                         {'slotId': options[0]['slotId']}, app_token=app_token)

    print('演示报名：新建 %d 条' % created)

    # 给第一个演示账号授予门店员工身份，否则员工工作台没有任何可操作数据
    users = http('GET', '/kangaroo/appuser/list?pageSize=200', token=token)
    keeper = next((u for u in users.get('rows', []) if u.get('nickname') == '晨光'), None)
    if keeper and store_ids:
        grants = http('GET', '/kangaroo/grant/list?appUserId=%s' % keeper['userId'], token=token)
        if not grants.get('rows'):
            r = http('POST', '/kangaroo/grant',
                     {'appUserId': keeper['userId'], 'storeId': store_ids[0], 'roleCode': 'manager'},
                     token=token)
            print('演示员工授权:', r.get('msg'))
        else:
            print('演示员工授权已存在')


def main():
    token = admin_login()
    print('管理员登录成功')

    # 门店
    store_ids = []
    existing = http('GET', '/kangaroo/store/list?pageSize=100', token=token)
    by_name = {r['storeName']: r['storeId'] for r in existing.get('rows', [])}
    for st in STORES:
        if st['storeName'] in by_name:
            sid = by_name[st['storeName']]
            print('门店已存在，复用: %s (%s)' % (st['storeName'], sid))
        else:
            res = http('POST', '/kangaroo/store', st, token=token)
            assert res.get('code') == 200, res
            sid = res['data']
            print('创建门店: %s (%s)' % (st['storeName'], sid))
        store_ids.append(sid)
        http('PUT', '/kangaroo/store/cert/%s?certified=true' % sid, token=token)

    # 岗位
    # 开始日期设为当天：报名页的日期选择器默认就是今天，
    # 把可开始日期推到未来会让"选今天"的报名被服务端正确拒绝，
    # 演示数据没必要制造这种阻塞。
    start_date = time.strftime('%Y-%m-%d')
    existing_jobs = http('GET', '/kangaroo/job/list?pageSize=200', token=token)
    job_by_title = {r['title']: r['jobId'] for r in existing_jobs.get('rows', [])}
    created = 0
    for j in JOBS:
        if j['title'] in job_by_title:
            jid = job_by_title[j['title']]
        elif j['jobType'] == 'online':
            # 岗位管理「新增」不再接受线上任务：那样只有 kg_job 一行，结算回落到岗位薪资、没有截止时间
            deadline = time.strftime('%Y-%m-%d 18:00', time.localtime(time.time() + 30 * 86400))
            body = {'storeId': store_ids[j['store']], 'title': j['title'], 'taskType': j['taskType'],
                    'deliverables': j['deliverables'], 'criteria': j['criteria'], 'deadline': deadline,
                    'commissionFen': int(round(j['salaryMin'] * 100)), 'quota': j['headcount'],
                    'estHours': j['estHours'], 'description': j['content'],
                    'requirements': [j['requirement']], 'skills': j['tags'].split(',')[:3]}
            res = http('POST', '/kangaroo/online-task', body, token=token)
            assert res.get('code') == 200, (j['title'], res)
            jid = res['data']['jobId']
            created += 1
        else:
            payload = {k: v for k, v in j.items() if k != 'store'}
            payload['storeId'] = store_ids[j['store']]
            payload['startDate'] = start_date
            res = http('POST', '/kangaroo/job', payload, token=token)
            assert res.get('code') == 200, (j['title'], res)
            jid = res['data']
            created += 1
        # 已经是招聘中的岗位不要再发布一次：published -> published 会被状态机正确拒绝
        cur = http('GET', '/kangaroo/job/%s' % jid, token=token)
        cur_status = (cur.get('data') or {}).get('status')
        if cur_status != 'published':
            r = http('PUT', '/kangaroo/job/changeStatus', {'jobId': jid, 'status': 'published'}, token=token)
            assert r.get('code') == 200, (j['title'], r)
    print('岗位：新建 %d 条，全部已发布（共 %d 条）' % (created, len(JOBS)))

    # 面试时段：给每个门店排 3 个未来时段
    base_ts = time.time() + 86400
    for idx, sid in enumerate(store_ids):
        for k in range(3):
            ts = base_ts + idx * 86400 + k * 3600
            s = time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(ts))
            e = time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(ts + 1200))
            http('POST', '/kangaroo/slot', {'storeId': sid, 'startTime': s, 'endTime': e}, token=token)
    print('面试时段已排布')

    seed_applications(token, store_ids)

    for t in ['volunteer', 'parttime', 'fulltime', 'online']:
        res = http('GET', '/app/job/list?type=%s&pageSize=50' % t)
        print('  小程序侧 %-9s 可见岗位: %d' % (t, res.get('total', 0)))
    print('完成')


if __name__ == '__main__':
    main()
