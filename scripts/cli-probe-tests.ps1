$ErrorActionPreference = 'Stop'
$probePath = Join-Path $PSScriptRoot 'cli-probe.ps1'
foreach ($path in @($probePath, $PSCommandPath)) {
    $tokens = $null; $errors = $null
    $null = [System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$errors)
    if ($errors.Count) { throw ($errors | Out-String) }
}
$tests = @'
import ast
import re
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace

source = Path(sys.argv[1]).read_text(encoding='utf-8-sig')
embedded = re.search(r"\$probeCode = @'\r?\n(.*?)\r?\n'@", source, re.S).group(1)
tree = ast.parse(embedded)
main = next(n for n in tree.body if isinstance(n, ast.Try))

def extract(node, namespace):
    exec(compile(ast.Module(body=[node], type_ignores=[]), '<extracted-probe>', 'exec'), namespace)

def pure_namespace():
    namespace = {'re': re}
    for node in tree.body:
        if isinstance(node, ast.FunctionDef) and node.name in ('require_live_thread', 'followup_prompt'):
            extract(node, namespace)
    tui = next(n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == 'Tui')
    extract(next(n for n in tui.body if isinstance(n, ast.FunctionDef) and n.name == 'contains'), namespace)
    return namespace

class Lock:
    def __enter__(self): return self
    def __exit__(self, *args): pass

class ProbeRegressionTests(unittest.TestCase):
    def resume_attempt(self, loaded, alive):
        calls = []
        def call(method, params):
            calls.append(method)
            if method == 'thread/loaded/list': return {'data': loaded}
            if method == 'thread/resume': return {'thread': {'id': 'disposable'}}
            raise AssertionError(method)
        namespace = pure_namespace()
        namespace.update(rpc=SimpleNamespace(call=call), tid='disposable',
                         tui=SimpleNamespace(p=SimpleNamespace(isalive=lambda: alive)),
                         RESULT={'checks': {}})
        start = next(i for i, n in enumerate(main.body) if isinstance(n, ast.Assign)
                     and isinstance(n.targets[0], ast.Name) and n.targets[0].id == 'ids')
        stop = next(i for i, n in enumerate(main.body) if isinstance(n, ast.Assign)
                    and isinstance(n.targets[0], ast.Name) and n.targets[0].id == 'joined')
        segment = ast.Module(body=main.body[start:stop + 1], type_ignores=[])
        failure = None
        try: exec(compile(segment, '<resume-boundary>', 'exec'), namespace)
        except RuntimeError as error: failure = error
        return calls, failure, namespace

    def test_missing_loaded_id_cannot_resume(self):
        calls, failure, _ = self.resume_attempt(['another-thread'], True)
        self.assertNotIn('thread/resume', calls)
        self.assertIsNotNone(failure)

    def test_dead_original_tui_cannot_resume(self):
        calls, failure, _ = self.resume_attempt(['disposable'], False)
        self.assertNotIn('thread/resume', calls)
        self.assertIsNotNone(failure)

    def test_live_loaded_thread_can_resume(self):
        calls, failure, namespace = self.resume_attempt(['disposable'], True)
        self.assertIsNone(failure)
        self.assertEqual(calls, ['thread/loaded/list', 'thread/resume'])
        self.assertTrue(namespace['RESULT']['checks']['nativeThreadLoadedWhileTuiAlive'])

    def test_prompt_does_not_contain_expected_even_normalized(self):
        namespace = pure_namespace()
        expected, prompt = namespace['followup_prompt']('0123456789abcdef')
        self.assertEqual(expected, 'CLI_FOLLOWUP_0123456789abcdef')
        self.assertNotIn(expected, prompt)
        self.assertNotIn(''.join(expected.split()), ''.join(prompt.split()))

    def followup(self, actual_response):
        namespace = pure_namespace()
        result = {'checks': {}}
        visible = SimpleNamespace(text='', lock=Lock(), p=SimpleNamespace(isalive=lambda: True))
        checked = []
        def contains(marker):
            checked.append(marker)
            return namespace['contains'](visible, marker)
        visible.contains = contains
        rpc = SimpleNamespace(notes=[])
        def call(method, params):
            self.assertEqual(method, 'turn/start')
            prompt = params['input'][0]['text']
            visible.text = prompt
            expected = 'CLI_FOLLOWUP_0123456789abcdef'
            response = expected if actual_response else prompt
            if actual_response: visible.text += '\n' + expected
            rpc.notes.extend([
                {'method': 'item/agentMessage/delta', 'params': {'threadId': 'disposable', 'turnId': 'turn', 'delta': response[:12]}},
                {'method': 'item/agentMessage/delta', 'params': {'threadId': 'disposable', 'turnId': 'turn', 'delta': response[12:]}},
                {'method': 'turn/completed', 'params': {'threadId': 'disposable', 'turn': {'id': 'turn', 'status': 'completed'}}},
            ])
            return {'turn': {'id': 'turn'}}
        rpc.call = call
        namespace.update(rpc=rpc, tid='disposable', RESULT=result, LIMIT=1,
                         uuid=SimpleNamespace(uuid4=lambda: SimpleNamespace(hex='0123456789abcdef')),
                         time=SimpleNamespace(monotonic=lambda: 0),
                         wait_until=lambda fn, seconds: fn())
        extract(next(n for n in main.body if isinstance(n, ast.FunctionDef) and n.name == 'followup'), namespace)
        namespace['followup'](visible, 'test')
        self.assertEqual(checked, ['CLI_FOLLOWUP_0123456789abcdef'])
        return result['checks']

    def test_prompt_echo_cannot_satisfy_response_marker(self):
        checks = self.followup(False)
        self.assertFalse(checks['testStreamed'])
        self.assertFalse(checks['testVisibleInTui'])

    def test_actual_assistant_response_satisfies_marker(self):
        checks = self.followup(True)
        self.assertTrue(checks['testStreamed'])
        self.assertTrue(checks['testVisibleInTui'])
        self.assertTrue(checks['testCompleted'])

unittest.main(argv=[sys.argv[0]], verbosity=2)
'@
$tests | & python - $probePath
exit $LASTEXITCODE
