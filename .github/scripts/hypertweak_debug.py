#!/usr/bin/env python3
"""Root-only HyperTweak debug client. All shell arguments use shlex, never string interpolation.
The user must enable the temporary debugging session in the module UI first.
"""
import argparse, json, shlex, subprocess, sys

def decode_reply(output):
    marker = 'data='
    if marker not in output:
        raise RuntimeError('Provider returned no JSON: ' + output[:300])
    return json.JSONDecoder().raw_decode(output.split(marker, 1)[1])[0]

def command(method, arg=None, serial=None, root_adbd=False, user=None):
    cmd = ['content', 'call']
    if user is not None: cmd += ['--user', str(user)]
    cmd += ['--uri', 'content://com.takekazex.hypertweak.debug', '--method', method]
    if arg is not None: cmd += ['--arg', arg]
    shell = shlex.join(cmd)
    if not root_adbd: shell = shlex.join(['su', '-c', shell])
    adb = ['adb'] + (['-s', serial] if serial else [])
    result = subprocess.run(adb + ['shell', shell], text=True, capture_output=True, timeout=60)
    if result.returncode: raise RuntimeError(result.stderr.strip() or result.stdout.strip())
    return decode_reply(result.stdout)

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial'); p.add_argument('--user',type=int)
    p.add_argument('--root-adbd', action='store_true', help='adbd already runs as UID 0')
    p.add_argument('--expected-revision',type=int)
    p.add_argument('operation',choices=['status','list','describe','get','set','patch','reset','collect','collection_status','collection_cancel'])
    p.add_argument('arguments',nargs='*')
    args=p.parse_args(); op=args.operation; av=args.arguments
    if op == 'set':
        if len(av)!=2: p.error('set KEY JSON_VALUE')
        arg=json.dumps({'protocol':1,'values':{av[0]:json.loads(av[1])}})
    elif op == 'patch':
        if len(av)!=1: p.error('patch JSON_FILE (contains values and/or remove)')
        with open(av[0]) as f: request=json.load(f)
        request['protocol']=1; arg=json.dumps(request)
    elif op == 'reset':
        if not av: p.error('reset KEY [KEY ...]')
        arg=json.dumps({'protocol':1,'remove':av})
    else:
        if len(av)>1: p.error('at most one argument')
        arg=av[0] if av else None
    if args.expected_revision is not None:
        if op not in ('set','patch','reset'): p.error('--expected-revision is for mutations')
        request=json.loads(arg); request['expected_revision']=args.expected_revision; arg=json.dumps(request)
    result=command(op,arg,args.serial,args.root_adbd,args.user)
    print(json.dumps(result,ensure_ascii=False,indent=2))
    return 0 if result.get('ok') else 1

if __name__=='__main__':
    try: sys.exit(main())
    except (ValueError, OSError, RuntimeError, subprocess.TimeoutExpired) as e:
        print(str(e),file=sys.stderr); sys.exit(2)
