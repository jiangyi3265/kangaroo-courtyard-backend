"""Pure validation for destructive migration tests; never opens a database."""
import re


ROLLBACK_AUTHORIZATION = {
    18: 'kg_allow_phone_rollback',
    19: 'kg_allow_identity_rollback',
    20: 'kg_allow_outbox_rollback',
    21: 'kg_allow_stay_activity_rollback',
    22: 'kg_allow_outbox_consent_rollback',
    23: 'kg_allow_salary_periods_rollback',
    24: 'kg_allow_job_compensation_rollback',
    25: 'kg_allow_national_cities_rollback',
    26: 'kg_allow_ops_rollback',
    27: 'kg_allow_city_alias_rollback',
    28: 'kg_allow_v28_rollback',
    29: 'kg_allow_v29_rollback',
    30: 'kg_allow_v30_rollback',
    31: 'kg_allow_v31_rollback',
    63: 'kg_allow_merchant_roles_rollback',
    64: 'kg_allow_role_switch_rollback',
    65: 'kg_allow_stay_comment_rollback',
    66: 'kg_allow_forms_rollback',
}


def validate_guard_databases(base, guard):
    prefix = r'(?:dkxy_acceptance_|kg_acceptance_|acceptance_)'
    if not re.fullmatch(prefix + r'[A-Za-z0-9_]+', base):
        raise ValueError('KG_DB must identify a dedicated acceptance schema')
    if (not re.fullmatch(prefix + r'[A-Za-z0-9_]+_guardtest', guard)
            or guard == base):
        raise ValueError('KG_GUARD_DB must be a distinct isolated *_guardtest schema')
    return True


def authorized_rollback_prefix(version, base, guard):
    validate_guard_databases(base, guard)
    variable = ROLLBACK_AUTHORIZATION.get(version)
    return ('set @' + variable + ' = 1;\n') if variable else ''
