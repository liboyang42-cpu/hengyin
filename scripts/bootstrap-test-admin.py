#!/usr/bin/env python3
"""Initialize an approved fresh test database; never rotate an existing/production administrator."""
import argparse
import hmac
import os
import re
import sys

DEFAULT_PASSWORDS = ('admin123', 'ry123', '123456', 'admin', 'ry')

def validate_target(schema, expected_uuid, confirm):
    if not confirm or not re.fullmatch(r'cy_e2e_[a-z0-9_]{1,48}', schema or ''):
        raise ValueError('An explicitly confirmed task-owned cy_e2e_ schema is required')
    if not re.fullmatch(r'[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}', expected_uuid or ''):
        raise ValueError('The independently verified test server UUID is required')

def validate_runtime_target(host, port):
    if sys.platform != 'linux':
        raise ValueError('Test administrator initialization requires the Linux test VPS or hosted CI')
    if host not in {'127.0.0.1', 'localhost', '::1'} or not 1 <= port <= 65535:
        raise ValueError('An explicit loopback test database host and valid port are required')

def validate_password(password):
    groups = [r'[a-z]', r'[A-Z]', r'[0-9]', r'[^a-zA-Z0-9]']
    if not isinstance(password, str) or len(password) < 12 or len(password.encode('utf-16-le')) // 2 > 20 or password in DEFAULT_PASSWORDS:
        raise ValueError('A non-default password of 12 to 20 characters, at most 20 UTF-16 code units, is required for compatibility with current administrator login')
    if sum(bool(re.search(group, password)) for group in groups) < 3 or '\x00' in password:
        raise ValueError('The password must contain at least three character categories')

def validate_fresh_users(rows):
    if len(rows) > 2:
        raise ValueError('Only a fresh bootstrap with at most admin and ry is eligible')
    names = [str(row[1]).lower() for row in rows]
    if len(set(names)) != len(names) or any(name not in ('admin', 'ry') for name in names):
        raise ValueError('Existing non-bootstrap accounts prohibit initialization')
    if any(row[3] is not None or row[4] != '0' for row in rows):
        raise ValueError('Used or deleted accounts cannot be initialized by this tool')

def initialize(connection, account, password, expected_uuid, crypto):
    validate_password(password)
    try:
        with connection.cursor() as cursor:
            cursor.execute('SELECT @@server_uuid, VERSION()')
            actual_uuid, version = cursor.fetchone()
            if str(actual_uuid).lower() != expected_uuid.lower() or not str(version).startswith('8.'):
                raise ValueError('Test target UUID or MySQL version differs from the verified target')
            cursor.execute('SELECT user_id,user_name,password,login_date,del_flag FROM sys_user ORDER BY user_id FOR UPDATE')
            rows = cursor.fetchall()
            validate_fresh_users(rows)
            if not rows:
                if account != 'admin':
                    raise ValueError('An empty environment must first initialize its canonical administrator')
                cursor.execute("SELECT role_key,status,del_flag FROM sys_role WHERE role_id=1")
                role = cursor.fetchone()
                if role != ('admin', '0', '0'):
                    raise ValueError('Canonical bootstrap administrator role is missing or altered')
                new_hash = crypto.crypt(password, crypto.mksalt(crypto.METHOD_BLOWFISH, rounds=4096))
                if not new_hash or not new_hash.startswith(('$2a$', '$2b$', '$2y$')):
                    raise ValueError('This runtime cannot generate compatible BCrypt; no fallback permitted')
                cursor.execute("INSERT INTO sys_user(user_id,user_name,nick_name,user_type,password,status,del_flag,create_by,create_time,pwd_update_date) VALUES(1,'admin','管理员','00',%s,'0','0','bootstrap',NOW(),NOW())", (new_hash,))
                if cursor.rowcount != 1:
                    raise ValueError('Administrator bootstrap insert failed')
                cursor.execute('INSERT INTO sys_user_role(user_id,role_id) VALUES(1,1)')
                if cursor.rowcount != 1:
                    raise ValueError('Administrator bootstrap role insert failed')
                cursor.execute('SELECT password FROM sys_user WHERE user_id=1')
                verified = cursor.fetchone()[0]
                if not hmac.compare_digest(crypto.crypt(password, verified), verified):
                    raise ValueError('Independent password readback failed')
                connection.commit()
                return
            selected = [row for row in rows if str(row[1]).lower() == account]
            if len(selected) != 1:
                raise ValueError('The requested bootstrap account is absent or ambiguous')
            row = selected[0]
            stored = row[2]
            if not isinstance(stored, str) or not stored.startswith(('$2a$', '$2b$', '$2y$')):
                raise ValueError('Only a proven untouched BCrypt bootstrap password is eligible')
            if not any(hmac.compare_digest(crypto.crypt(candidate, stored), stored) for candidate in DEFAULT_PASSWORDS):
                raise ValueError('Existing administrator password is not a known bootstrap default; no rotation permitted')
            new_hash = crypto.crypt(password, crypto.mksalt(crypto.METHOD_BLOWFISH, rounds=4096))
            if not new_hash or not new_hash.startswith(('$2a$', '$2b$', '$2y$')):
                raise ValueError('This runtime cannot generate compatible BCrypt; no fallback permitted')
            cursor.execute('UPDATE sys_user SET password=%s,update_time=NOW(),pwd_update_date=NOW() WHERE user_id=%s AND password=%s',
                           (new_hash, row[0], stored))
            if cursor.rowcount != 1:
                raise ValueError('Bootstrap state changed; initialization was not committed')
            cursor.execute('SELECT password FROM sys_user WHERE user_id=%s', (row[0],))
            verified = cursor.fetchone()[0]
            if not hmac.compare_digest(crypto.crypt(password, verified), verified):
                raise ValueError('Independent password readback failed')
        connection.commit()
    except BaseException:
        connection.rollback()
        raise

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--schema', required=True)
    parser.add_argument('--expected-server-uuid', required=True)
    parser.add_argument('--confirm-test-target', action='store_true')
    parser.add_argument('--account', choices=('admin', 'ry'), default='admin')
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=3306)
    parser.add_argument('--db-user', default='root')
    parser.add_argument('--db-password-env', default='CHENGYIN_BOOTSTRAP_TEST_DB_PASSWORD')
    parser.add_argument('--admin-password-env', default='CHENGYIN_BOOTSTRAP_TEST_ADMIN_PASSWORD')
    args = parser.parse_args()
    try:
        validate_runtime_target(args.host, args.port)
        validate_target(args.schema, args.expected_server_uuid, args.confirm_test_target)
        password = os.environ.get(args.admin_password_env)
        validate_password(password)
        import crypt  # Python 3.12 Linux system BCrypt; unavailable support is a hard failure.
        import pymysql  # Existing VPS database tooling; this does not add an application dependency.
        connection = pymysql.connect(host=args.host, port=args.port, user=args.db_user,
            password=os.environ.get(args.db_password_env, ''), database=args.schema,
            charset='utf8mb4', autocommit=False)
        try:
            initialize(connection, args.account, password, args.expected_server_uuid, crypt)
        finally:
            connection.close()
        print('Fresh test administrator initialized; roles and other accounts preserved. Password not printed.')
        return 0
    except Exception as error:
        print('Administrator initialization refused or failed (%s); no success claimed.' % type(error).__name__)
        return 1

if __name__ == '__main__':
    raise SystemExit(main())
