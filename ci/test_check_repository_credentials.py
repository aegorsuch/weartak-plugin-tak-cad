import contextlib
import io
import unittest

from check_repository_credentials import main


class RepositoryCredentialsTest(unittest.TestCase):
    def check(self, environment, expected):
        output = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            self.assertEqual(expected, main(environment))
        for value in environment.values():
            if value.strip():
                self.assertNotIn(value, output.getvalue())

    def test_missing_and_partial_pairs_fail(self):
        self.check({}, 1)
        self.check({"ARTIFACTORY_CONSUME_USER": "secret-user"}, 1)
        self.check({"TAK_PASSWORD": "secret-password"}, 1)

    def test_blank_primary_and_independent_fallbacks(self):
        self.check({
            "ARTIFACTORY_CONSUME_USER": "  ",
            "ARTIFACTORY_USER": "secret-user",
            "ARTIFACTORY_CONSUME_PASSWORD": "secret-password",
        }, 0)
        self.check({"TAK_USERNAME": "secret-user", "TAK_PASSWORD": "secret-password"}, 0)
