"""locust recommend-only harness — F-4 evidence.

Why a separate file: locust's --tags filter excludes from the scheduled task list,
but ALL User classes declared in the file are still instantiated. AuthOnlyUser /
MixLikeDetailUser keep their classes defined in the master ``locustfile.py``, but
each `from locustfile import ...` below forces only RecommendUser into this file's
locust registry when locust loads it as `-f locustfile_recommend.py`. That sidesteps
"No tasks defined on AuthOnlyUser" instantiation crashes while keeping the master
file as a single source of truth.

Usage:
  uv run locust -f locustfile_recommend.py --headless --host=http://127.0.0.1:8080 \\
      -u 100 -r 50 -t 30s --csv=evidence/f4-p95-before
  uv run locust -f locustfile_recommend.py --headless --host=http://127.0.0.1:8080 \\
      -u 100 -r 50 -t 30s --csv=evidence/f4-p95-after
"""
from locustfile import RecommendUser  # noqa: F401  (registration side-effect)

if __name__ == "__main__":
    import locust.main
    locust.main.main()
