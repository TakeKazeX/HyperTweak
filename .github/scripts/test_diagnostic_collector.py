import pathlib, shlex, subprocess, tempfile, unittest
ROOT=pathlib.Path(__file__).resolve().parents[2]

class CollectorStreamsTest(unittest.TestCase):
    def run_capture(self, code, cap):
        script=(ROOT/'app/src/main/assets/diagnostics/collector.sh').read_text()
        functions=script[script.index('file_size()'):script.index('\n{\n    echo "=== Collected at ==="')]
        with tempfile.TemporaryDirectory() as directory:
            text='WORK='+shlex.quote(directory)+'\n'+functions+'\nHT_CAP_BYTES='+str(cap)+'\ncapture sample.txt python3 -c '+shlex.quote(code)+'\ntrue\n'
            result=subprocess.run(['sh','-c',text],capture_output=True,text=True,timeout=25)
            self.assertEqual(0,result.returncode,result.stderr)
            root=pathlib.Path(directory)
            return ((root/'sample.txt').read_text(), (root/'sample.txt.error.txt').read_text() if (root/'sample.txt.error.txt').exists() else '', (root/'extra-status.txt').read_text(),list(root.glob('*fifo')))
    def test_keeps_newest_stdout_and_original_command_failure(self):
        output,error,status,fifos=self.run_capture("import sys;print('A'*20000+'END');print('stderr detail',file=sys.stderr);sys.exit(7)",1000)
        self.assertIn('END',output)
        self.assertLess(len(output),1200)
        self.assertIn('stderr detail',error)
        self.assertIn('exit=7',status)
        self.assertFalse(fifos)
    def test_stderr_is_bounded_independently(self):
        output,error,status,_=self.run_capture("import sys;print('OK');print('B'*100000+'LAST',file=sys.stderr)",1000)
        self.assertEqual('OK\n',output)
        self.assertTrue(error.endswith('LAST\n'))
        self.assertLessEqual(len(error),65536)
        self.assertIn('exit=0',status)
