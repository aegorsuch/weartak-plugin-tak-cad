"""Report repository credential availability without exposing credential values."""

import os
import sys

USER_KEYS = ("ARTIFACTORY_CONSUME_USER", "ARTIFACTORY_USER", "TAK_USERNAME")
PASSWORD_KEYS = ("ARTIFACTORY_CONSUME_PASSWORD", "ARTIFACTORY_PASSWORD", "TAK_PASSWORD")


def has_value(environment, keys):
    return any(environment.get(key, "").strip() for key in keys)


def main(environment=None):
    environment = os.environ if environment is None else environment
    user = has_value(environment, USER_KEYS)
    password = has_value(environment, PASSWORD_KEYS)
    print(f"TAK repository user available: {bool(user)}")
    print(f"TAK repository password available: {bool(password)}")
    print(f"TAK repository credentials enabled: {bool(user and password)}")
    if not (user and password):
        print("Repository credentials unavailable: runner requires existing read-only "
              "consumer user/password pair; no values were read from variable APIs.",
              file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
