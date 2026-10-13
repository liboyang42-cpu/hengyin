"""Fail closed on unobserved/unknown network destinations in the startup trace."""
import ipaddress
import os
import platform
import re
import sys


def docker_endpoint(environment, system):
    """Only hosted CI or an explicitly identified test VPS may create fixtures."""
    if system != 'Linux':
        raise ValueError('Startup fixtures require the Linux CI runner or approved test VPS')
    if (environment.get('GITHUB_ACTIONS') == 'true'
            and environment.get('RUNNER_ENVIRONMENT') == 'github-hosted'):
        return 'unix:///var/run/docker.sock'
    if (environment.get('CHENGYIN_STARTUP_TARGET') == 'test-vps'
            and environment.get('CHENGYIN_STARTUP_DOCKER_ENDPOINT') == 'unix:///var/run/docker.sock'
            and environment.get('CHENGYIN_STARTUP_DOCKER_ID', '').strip()):
        return 'unix:///var/run/docker.sock'
    raise ValueError('Explicit hosted CI or test VPS endpoint and daemon identity required')


def verify(text, mysql_ip, redis_ip):
    allowed = {(mysql_ip, 3306), (redis_ip, 6379)}
    count = 0
    for line in text.splitlines():
        if 'AF_INET' not in line:
            continue
        matches = re.findall(r'inet_addr\("([^"]+)"\)|inet_pton\(AF_INET6, "([^"]+)"', line)
        ports = re.findall(r'(?:sin_port|sin6_port)=htons\((\d+)\)', line)
        if len(matches) != 1 or len(ports) != 1:
            raise ValueError('Unrecognized IP destination in strace; do not silently ignore it')
        ipv4, ipv6 = matches[0]
        address = ipaddress.ip_address(ipv4 or ipv6)
        address = getattr(address, 'ipv4_mapped', None) or address
        port = int(ports[0])
        if port == 53:
            raise ValueError('Unexpected DNS/provider resolution attempt')
        if not address.is_loopback and (str(address), port) not in allowed:
            raise ValueError(f'Unexpected destination: {address}:{port}')
        count += 1
    if count == 0:
        raise ValueError('No socket observations: tracing must actually run')
    return count


if __name__ == '__main__':
    if sys.argv[1:] == ['--docker-endpoint']:
        print(docker_endpoint(os.environ, platform.system()))
        sys.exit(0)
    with open(sys.argv[1]) as source:
        observed = verify(source.read(), sys.argv[2], sys.argv[3])
    print(f'PASS observed {observed} socket destinations; external destinations/DNS attempts=0')
