#!/usr/bin/env python3
"""Prepare uniquely named, empty loopback MySQL schemas for GitHub Actions only."""
import os
import re
import secrets
import subprocess
from pathlib import Path

# The external-identity and renewal fixtures create and verify their own schemas.
SCHEMAS = {
    'AI': ('xiaozhi_ai_test_', True),
    'CONVERSATION': ('xiaozhi_conversation_test_', True),
    'SUPPORT': ('xiaozhi_support_test_', True),
    'LEGACY': ('xiaozhi_legacy_test_', True),
    'ATTACHMENT': ('xiaozhi_attachment_test_', True),
    'ANNOTATION': ('xiaozhi_annotation_test_', True),
    'REALTIME': ('xiaozhi_realtime_test_', True),
    'SUPPORT_MEDIA': ('xiaozhi_support_media_test_', True),
    'SUMMARY': ('xiaozhi_summary_test_', True),
    'SUMMARY_ASSISTANCE': ('xiaozhi_summary_test_', True),
    'AGENT_TOOL': ('xiaozhi_agent_tool_test_', True),
    'EXTERNAL': ('xiaozhi_external_test_', False),
    'EXTERNAL_APP': ('xiaozhi_external_test_', False),
    'RENEWAL': ('xiaozhi_renewal_test_', False),
    'FULL': ('xiaozhi_full_test_', True),
}


def plan(tag):
    if not re.fullmatch(r'[a-z0-9_]{1,24}', tag):
        raise ValueError('Invalid CI schema suffix')
    names = {key: prefix + key.lower() + '_' + tag for key, (prefix, _) in SCHEMAS.items()}
    if len(set(names.values())) != len(names) or any(len(name) > 64 for name in names.values()):
        raise ValueError('Invalid or duplicate CI schema names')
    return names


def environment(names, password):
    options = '?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&socketTimeout=30000&connectTimeout=5000'
    def url(name): return 'jdbc:mysql://127.0.0.1:34776/' + name + options
    result = {}
    for key, name in names.items():
        if key != 'FULL':
            result[key + '_TEST_JDBC_URL'] = url(name)
            result[key + '_TEST_DB_PASSWORD'] = password
    result.update(SPRING_DATASOURCE_DRUID_URL=url(names['FULL']), SPRING_DATASOURCE_DRUID_USERNAME='root',
                  SPRING_DATASOURCE_DRUID_PASSWORD=password, SPRING_DATASOURCE_DRUID_INITIAL_SIZE='1',
                  SPRING_DATASOURCE_DRUID_MIN_IDLE='1', SPRING_DATASOURCE_DRUID_MAX_ACTIVE='8',
                  SPRING_DATA_REDIS_HOST='127.0.0.1', SPRING_DATA_REDIS_PORT='34777')
    return result


def main():
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise RuntimeError('CI database creation is restricted to GitHub Actions')
    password = os.environ.get('CI_MYSQL_PASSWORD', '')
    output = os.environ.get('GITHUB_ENV', '')
    if not password or '\n' in password or not output:
        raise RuntimeError('Missing CI database credential or GitHub environment file')
    tag = secrets.token_hex(8)
    names = plan(tag)
    # Fixed host/port, identifiers validated above; password goes only via process environment.
    mysql = ['mysql', '--no-defaults', '--protocol=TCP', '--host=127.0.0.1', '--port=34776', '--user=root', '--batch', '--skip-column-names']
    child_env = dict(os.environ, MYSQL_PWD=password)
    quoted = ','.join("'" + name + "'" for name in names.values())
    check = subprocess.run(mysql, input='SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME IN (' + quoted + ')',
                           text=True, env=child_env, capture_output=True, check=True)
    if check.stdout.strip():
        raise RuntimeError('Refusing to reuse an existing CI schema')
    create = '\n'.join('CREATE DATABASE `' + names[key] + '` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;'
                       for key, (_, precreate) in SCHEMAS.items() if precreate)
    subprocess.run(mysql, input=create, text=True, env=child_env, check=True)
    with Path(output).open('a', encoding='utf-8') as target:
        for key, value in environment(names, password).items():
            target.write(key + '=' + value + '\n')
    print('Created isolated CI schemas; external and renewal fixtures create their own.')


if __name__ == '__main__':
    main()
