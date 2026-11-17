"""Only local test fixtures: model an elapsed appointment without waiting days.
Does not alter HTTP or product code. Each test calls this explicitly after booking.
"""
from urllib.parse import urlparse
import os
import sys
from test_kangaroo_flow import BASE, http, sql_exec, sql

def advance_interview_fixture(app_id):
    assert urlparse(BASE).hostname in ('localhost', '127.0.0.1')
    expected_db = os.environ.get('KG_DB', '')
    assert expected_db.startswith(('dkxy_acceptance_', 'kg_acceptance_', 'acceptance_')), 'Explicit isolated KG_DB required'
    assert sql('select database()')[0][0] == expected_db, 'Actual database must match isolated KG_DB'
    assert http('GET', '/app/auth/ping')[1]['data']['wxMock'] is True
    app_id = int(app_id)
    assert app_id > 0
    sql_exec("set time_zone='+08:00'; update kg_interview set start_time=date_sub(now(),interval 1 minute),end_time=date_add(now(),interval 19 minute) "
             "where app_id=%d and exists(select 1 from kg_application a where a.app_id=%d and a.status='interview_booked')" % (app_id, app_id))

if __name__ == '__main__':
    advance_interview_fixture(sys.argv[1])
    print('Local interview fixture clock advanced')
