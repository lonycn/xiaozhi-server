import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('ci_prepare', Path(__file__).with_name('ci_prepare_databases.py'))
ci = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ci)


class SchemaPreparationTest(unittest.TestCase):
    def test_plan_is_unique_and_self_creating_fixtures_are_separate(self):
        names = ci.plan('abc123')
        self.assertEqual(len(names), len(set(names.values())))
        self.assertTrue(names['EXTERNAL_APP'].startswith('xiaozhi_external_test_'))
        self.assertTrue(names['RENEWAL'].startswith('xiaozhi_renewal_test_'))
        self.assertFalse(ci.SCHEMAS['EXTERNAL'][1]); self.assertFalse(ci.SCHEMAS['EXTERNAL_APP'][1]); self.assertFalse(ci.SCHEMAS['RENEWAL'][1])
        self.assertTrue(names['SUMMARY_ASSISTANCE'].startswith('xiaozhi_summary_test_'))
        for invalid in ['../x', 'A', 'a;DROP', '', 'x' * 25]:
            with self.assertRaises(ValueError): ci.plan(invalid)
        env = ci.environment(names, 'ci-fixture')
        self.assertTrue(all('127.0.0.1:34776/' in value for key, value in env.items() if key.endswith('_JDBC_URL')))
        self.assertIn('AGENT_TOOL_TEST_JDBC_URL', env)
        self.assertIn('EXTERNAL_APP_TEST_JDBC_URL', env)
        self.assertIn('RENEWAL_TEST_JDBC_URL', env)
        self.assertEqual(env['SPRING_DATA_REDIS_PORT'], '34777')

    def test_main_refuses_non_ci_and_existing_schema_without_mutation(self):
        with patch.dict(os.environ, {'GITHUB_ACTIONS': 'false'}, clear=True), patch.object(ci.subprocess, 'run') as run:
            with self.assertRaises(RuntimeError): ci.main()
            run.assert_not_called()
        with tempfile.TemporaryDirectory() as temp:
            env = {'GITHUB_ACTIONS': 'true', 'CI_MYSQL_PASSWORD': 'fixture', 'GITHUB_ENV': str(Path(temp) / 'env')}
            with patch.dict(os.environ, env, clear=True), patch.object(ci.subprocess, 'run', return_value=type('R', (), {'stdout': 'existing\n'})()) as run:
                with self.assertRaisesRegex(RuntimeError, 'existing'): ci.main()
                self.assertEqual(run.call_count, 1)

    def test_creation_only_new_schemas_and_local_connection(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / 'env'
            env = {'GITHUB_ACTIONS': 'true', 'CI_MYSQL_PASSWORD': 'fixture', 'GITHUB_ENV': str(output)}
            with patch.dict(os.environ, env, clear=True), patch.object(ci.secrets, 'token_hex', return_value='abc123'), patch.object(ci.subprocess, 'run', return_value=type('R', (), {'stdout': ''})()) as run:
                ci.main()
            self.assertEqual(run.call_count, 2)
            self.assertIn('--host=127.0.0.1', run.call_args_list[0].args[0])
            self.assertIn('--port=34776', run.call_args_list[0].args[0])
            sql = run.call_args_list[1].kwargs['input']
            for key, name in ci.plan('abc123').items():
                self.assertEqual(name in sql, ci.SCHEMAS[key][1])
            self.assertNotRegex(sql.upper(), r'\b(DROP|TRUNCATE|FLUSH|DELETE)\b')
            self.assertIn('EXTERNAL_APP_TEST_JDBC_URL=', output.read_text())


if __name__ == '__main__':
    unittest.main()
