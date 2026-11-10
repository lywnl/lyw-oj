#!/usr/bin/env python3
"""从环境生成未跟踪的 Canal 运行配置；只生成文件，不创建账号或启动服务。"""
from pathlib import Path
import argparse
import json
import os
import re


def property_value(value):
    result = []
    for char in str(value):
        code = ord(char)
        if char in "\\:=#! ": result.append("\\" + char)
        elif char == "\n": result.append("\\n")
        elif char == "\r": result.append("\\r")
        elif char == "\t": result.append("\\t")
        elif code > 127:
            data = char.encode('utf-16-be').hex()
            result.extend("\\u" + data[i:i + 4] for i in range(0, len(data), 4))
        else: result.append(char)
    return ''.join(result)


def prepare(output, environment, source_file, position, timestamp):
    database = os.environ.get('CANAL_DB_NAME', 'lywoj' if environment == 'dev' else '')
    username = os.environ.get('CANAL_DB_USERNAME', 'canal_sync')
    password = os.environ.get('CANAL_DB_PASSWORD', '')
    host = os.environ.get('CANAL_DB_HOST', 'oj-mysql-server')
    try: port = int(os.environ.get('CANAL_DB_PORT', '3306'))
    except ValueError: raise ValueError('数据库端口必须是整数') from None
    if not re.fullmatch(r'[A-Za-z0-9_-]+', database) or not re.fullmatch(r'[A-Za-z0-9_]+', username):
        raise ValueError('必须提供合法业务库名和专用账号；生产不允许推断库名')
    if not password: raise ValueError('CANAL_DB_PASSWORD 不得为空')
    if len(password.encode('utf-8')) > 32 or len(username.encode('utf-8')) > 32:
        raise ValueError('Canal 1.1.8 的副本注册报文要求账号及密码不超过 32 个 UTF-8 字节')
    if not re.fullmatch(r'[A-Za-z0-9._-]+\.[0-9]+', source_file) or position < 4:
        raise ValueError('必须提供经基线核对的明确 binlog 起点，不支持自动取最新位置')
    if timestamp <= 0:
        raise ValueError('必须同时提供起点对应的源时间（Unix 毫秒），避免 Canal 回退扫描历史 DDL')
    if not re.fullmatch(r'[A-Za-z0-9._-]+', host) or not 1 <= port <= 65535: raise ValueError('数据库地址无效')
    output.mkdir(parents=True, exist_ok=True)
    instance = {
        'canal.instance.mysql.slaveId': os.environ.get('CANAL_SLAVE_ID', '1001'),
        'canal.instance.master.address': f'{host}:{port}',
        'canal.instance.master.journal.name': source_file,
        'canal.instance.master.position': position,
        'canal.instance.master.timestamp': timestamp,
        'canal.instance.dbUsername': username,
        'canal.instance.dbPassword': password,
        'canal.instance.connectionCharset': 'UTF-8',
        'canal.instance.filter.regex': re.escape(database) + r'\.tb_question',
        # TSDB 会在表白名单之前枚举 schema；先排除系统库，专用账号无需全库 SELECT。
        'canal.instance.filter.black.regex': r'(information_schema|performance_schema|mysql|sys)\..*',
        'canal.instance.tsdb.enable': 'true',
        'canal.instance.gtidon': 'false',
    }
    overrides = {
        'canal.serverMode': 'tcp', 'canal.destinations': 'question-sync', 'canal.auto.scan': 'false',
        'canal.file.data.dir': '/state', 'canal.file.flush.period': '1000',
        'canal.instance.tsdb.dir': '/state/${canal.instance.destination:}',
        'canal.instance.global.spring.xml': 'classpath:spring/file-instance.xml',
        'canal.instance.binlog.format': 'ROW', 'canal.instance.binlog.image': 'FULL',
        'canal.instance.filter.query.ddl': 'false', 'canal.instance.filter.query.dml': 'false',
        'canal.instance.filter.transaction.entry': 'false',
        'canal.instance.memory.buffer.size': '1024', 'canal.instance.parser.parallel': 'false',
    }
    for name, values in [('instance.properties', instance), ('canal-overrides.properties', overrides)]:
        path = output / name
        path.write_text(''.join(key + '=' + property_value(value) + '\n' for key, value in values.items()), encoding='ascii')
        path.chmod(0o600)
    # 生成供 DBA 审阅的账号脚本；不会自动执行，也不修改全局认证方式。
    literal = password.replace('\\', '\\\\').replace("'", "''").replace('\n', '\\n').replace('\r', '\\r')
    grant_database = database.replace('_', r'\_')
    sql = (f"CREATE USER '{username}'@'%' IDENTIFIED WITH mysql_native_password BY '{literal}';\n"
           f"GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO '{username}'@'%';\n"
           f"GRANT SELECT ON `{grant_database}`.* TO '{username}'@'%';\n")
    account = output / 'account.sql'
    account.write_text(sql, encoding='utf-8'); account.chmod(0o600)
    summary = {'database': database, 'destination': 'question-sync', 'initialFile': source_file,
               'initialPosition': position, 'sourceTimestamp': timestamp, 'sourceAddress': f'{host}:{port}', 'configGenerated': True}
    (output / 'manifest.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return summary


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env', choices=['dev', 'prod'], required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--source-file', required=True)
    parser.add_argument('--source-position', type=int, required=True)
    parser.add_argument('--source-timestamp', type=int, required=True, help='与起点一起核对的 MySQL 源 Unix 毫秒时间')
    args = parser.parse_args()
    try:
        print(json.dumps(prepare(args.output_dir, args.env, args.source_file, args.source_position, args.source_timestamp), ensure_ascii=False))
    except ValueError as invalid:
        parser.exit(2, '生成失败：' + str(invalid) + '\n')
    except OSError:
        parser.exit(2, '生成失败：检查输出目录及权限。\n')
