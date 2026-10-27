import importlib.util
import json
import pathlib
import shlex
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('ht_debug', pathlib.Path(__file__).with_name('hypertweak_debug.py'))
client = importlib.util.module_from_spec(spec)
spec.loader.exec_module(client)

class DebugClientTest(unittest.TestCase):
    def test_bundle_parser_ignores_braces_and_commas_inside_strings(self):
        value={'ok':True,'effective':'hello }, ok=false] data=world'}
        self.assertEqual(value,client.decode_reply('Result: Bundle[{data='+json.dumps(value)+', ok=true}]'))
    def test_payload_is_shell_data_at_both_boundaries(self):
        payload=json.dumps({'values': {'key': "'; $(touch /tmp/not-executed); `id`"}})
        class Result:
            returncode=0; stderr=''; stdout='Result: Bundle[{data={"ok": true}, ok=true}]'
        with patch.object(client.subprocess,'run',return_value=Result()) as run:
            client.command('patch',payload,serial='device',user=10)
            args=run.call_args.args[0]
            self.assertEqual(['adb','-s','device','shell'],args[:4])
            root_args=shlex.split(args[4])
            self.assertEqual(['su','-c'],root_args[:2])
            content_args=shlex.split(root_args[2])
            self.assertEqual(payload,content_args[-1])
            self.assertEqual(['--user','10'],content_args[2:4])
    def test_missing_reply_is_not_success(self):
        with self.assertRaises(RuntimeError): client.decode_reply('SecurityException')
