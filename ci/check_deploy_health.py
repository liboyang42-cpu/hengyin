#!/usr/bin/env python3
"""Read protected Actuator health without putting its bearer credential in argv/logs."""
import base64
import json
import os
import re
import stat
import subprocess
import sys

HEALTH_URL = 'http://127.0.0.1:8080/actuator/health'


class HealthCheckError(Exception):
    pass


def private_token(path):
    if not path or not os.path.isabs(path):
        raise HealthCheckError('token-file-path')
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(fd, 'rb') as source:
            metadata = os.fstat(source.fileno())
            if not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.geteuid():
                raise HealthCheckError('token-file-owner-or-type')
            if stat.S_IMODE(metadata.st_mode) not in (0o400, 0o600):
                raise HealthCheckError('token-file-permissions')
            raw = source.read(16385)
    except OSError:
        raise HealthCheckError('token-file-unavailable') from None
    # The runtime authenticates the signature/session. Here reject malformed input/header injection.
    if not re.fullmatch(rb'[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', raw) or len(raw) > 16384:
        raise HealthCheckError('token-format')
    try:
        parts = raw.decode('ascii').split('.')
        decode = lambda value: base64.b64decode(value + '=' * (-len(value) % 4), altchars=b'-_', validate=True)
        header, claims = (json.loads(decode(value)) for value in parts[:2])
        if not isinstance(header, dict) or header.get('alg') != 'HS512' or not isinstance(claims, dict) or len(decode(parts[2])) != 64:
            raise ValueError()
    except (ValueError, TypeError, UnicodeError):
        raise HealthCheckError('token-format') from None
    return raw.decode('ascii')


def require_up(status_code, body):
    if status_code != 200:
        raise HealthCheckError('http-status')
    try:
        result = json.loads(body)
        components = result['components']
        ready = result['status'] == 'UP' and components['db']['status'] == 'UP' and components['redis']['status'] == 'UP'
    except (ValueError, KeyError, TypeError):
        raise HealthCheckError('health-contract') from None
    if not ready:
        raise HealthCheckError('health-down')


def check(path):
    token = private_token(path)
    # --disable suppresses ~/.curlrc, --noproxy fixes loopback, no -L or credentials in argv.
    config = 'header = "Authorization: Bearer ' + token + '"\n'
    try:
        response = subprocess.run(
            ['curl', '--disable', '--noproxy', '*', '--silent', '--max-time', '3',
             '--max-filesize', '1048576', '--proto', '=http', '--request', 'GET',
             '--config', '-', '--write-out', '\n%{http_code}', HEALTH_URL],
            input=config, text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            timeout=5, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise HealthCheckError('health-transport') from None
    if response.returncode != 0:
        raise HealthCheckError('health-transport')
    try:
        body, code = response.stdout.rsplit('\n', 1)
        status_code = int(code)
    except (ValueError, AttributeError):
        raise HealthCheckError('http-response') from None
    require_up(status_code, body)


def main(args):
    try:
        if len(args) == 2 and args[0] == '--validate-token-file':
            private_token(args[1])
        elif len(args) == 1:
            check(args[0])
        else:
            raise HealthCheckError('arguments')
    except HealthCheckError as error:
        print('deploy health failed: ' + str(error), file=sys.stderr)
        return 1
    print('deploy health ready' if len(args) == 1 else 'deploy health authorization file validated')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
