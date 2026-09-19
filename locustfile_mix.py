"""locust mix-like-detail-only harness — F-5 evidence.

Why a separate file: see ``locustfile_recommend.py`` docstring — locust's --tags
filter still instantiates ALL User classes in the file. Splitting per-tag into
separate files is the cleanest workaround; the master ``locustfile.py`` keeps
all three classes for backward-compat / doc references in tickets.

Usage:
  uv run locust -f locustfile_mix.py --headless --host=http://127.0.0.1:8080 \\
      -u 50 -r 25 -t 30s --csv=evidence/f5-p99
"""
from locustfile import MixLikeDetailUser  # noqa: F401  (registration side-effect)

if __name__ == "__main__":
    import locust.main
    locust.main.main()
