#!/usr/bin/env python3
"""Create clearly synthetic desktop acceptance sessions in a new/empty data directory.
No model requests or real workspace modifications are performed.
"""
import argparse
import json
import uuid
from pathlib import Path
from datetime import datetime, timezone, timedelta

parser = argparse.ArgumentParser()
parser.add_argument('--data-dir', type=Path, required=True)
args = parser.parse_args()
root = args.data_dir.resolve()
if root.exists() and any(root.iterdir()):
    parser.error('Use a new or empty directory; existing data will not be overwritten')
root.mkdir(parents=True, exist_ok=True)
workspace = root / 'synthetic-workspace'
workspace.mkdir()
(workspace / 'sample.kt').write_text('fun greeting() = "Hello"\n')
(workspace / 'notes.txt').write_text('Synthetic fixture only.\n')
now = datetime.now(timezone.utc)

def session(name, payloads, age=0):
    folder = root / 'sessions' / name
    folder.mkdir(parents=True)
    records = [('session.workspace', {'root': str(workspace)})] + payloads
    events = [dict(schemaVersion=1, id=str(uuid.uuid4()), sessionId=name, seq=i,
                   timestamp=(now - timedelta(days=age)).isoformat().replace('+00:00', 'Z'), type=t, payload=p)
              for i, (t, p) in enumerate(records)]
    (folder / 'events.jsonl').write_text(''.join(json.dumps(e, ensure_ascii=False) + '\n' for e in events))

def message(role, content, **metadata):
    return ('message', dict(role=role, content=content, **metadata))

def origin(model):
    return {'kind': 'MODEL', 'modelId': model}

calls = [dict(id=f'call-{i}', name='read_file', arguments=json.dumps({'path': path}))
         for i, path in enumerate(['sample.kt', 'notes.txt'])]
main = [message('user', '[Макет] Сравнение моделей и группы инструментов'),
        message('assistant', 'Сначала прочитаю файлы. Это синтетический сценарий.', origin=origin('deepseek-flash'), toolCalls=calls)]
for call, path in zip(calls, ['sample.kt', 'notes.txt']):
    main += [('activity', dict(id=f'activity-{call["id"]}', name='read_file', target=path,
                              status='completed', output='{"ok":true}', toolCallId=call['id'])),
             message('tool', '{"ok":true}', toolCallId=call['id'])]
main += [message('assistant', 'Пример кода:\n```kotlin\nfun greeting() = "Hello"\n```\nНикакие команды здесь не выполнялись.', origin=origin('deepseek-flash')),
         message('user', 'Покажи подпись другой модели'),
         message('assistant', 'Этот ответ принадлежит fixture/gemma-4. Live Gemma не проверена.', origin=origin('fixture/gemma-4')),
         message('assistant', 'Старое сообщение без сохранённого автора.'),
         message('assistant', 'Синтетический offline-ответ.', origin={'kind':'OFFLINE'}),
         ('run.completed', {})]
session('models-and-tools', main)
session('failed-task', [message('user', '[Макет] Ошибка и возврат текста'), ('run.started', {}),
                        ('run.failed', {'message': 'Синтетическая ошибка соединения. Запрос не выполнялся.'})], age=1)
question = dict(id='pending-question', name='ask_user', arguments=json.dumps({'question':'Какой файл проверить?','options':['sample.kt','notes.txt']}))
session('pending-question', [('run.started', {}), message('user','[Макет] Сохранённый вопрос'),
                             message('assistant', None, toolCalls=[question], origin=origin('deepseek-flash'))], age=1)
long = [message('user', '[Макет] Длинная история — 1200 событий')]
for i in range(1200):
    long.append(message('user' if i % 2 == 0 else 'assistant', f'Синтетическое сообщение {i}. Никаких внешних действий.', **({} if i % 2 == 0 else {'origin': origin('deepseek-flash')})))
long.append(('run.completed', {}))
session('long-history', long, age=3)
session('applied-diff', [message('user', '[Макет] Просмотр diff'),
                        message('assistant', 'Макет правки; применять и откатывать нечего.', toolCalls=[dict(id='diff-call',name='edit_file',arguments='{}')], origin=origin('deepseek-flash')),
                        ('activity',dict(id='diff-activity',name='edit_file',target='sample.kt',status='completed',output='{"ok":true}',toolCallId='diff-call',diff='--- a/sample.kt\n+++ b/sample.kt\n@@ -1 +1 @@\n-fun greeting() = "Hello there"\n+fun greeting() = "Hello"\n')),
                        message('tool','{"ok":true}',toolCallId='diff-call'),('run.completed',{})], age=3)
print(f'Synthetic UI fixtures created: {root}')
