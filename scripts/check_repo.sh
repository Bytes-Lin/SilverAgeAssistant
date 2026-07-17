#!/usr/bin/env bash
set -euo pipefail

echo "Checking for obvious secret patterns..."
if git grep -nE '(sk-[A-Za-z0-9_-]{16,}|DASHSCOPE_API_KEY\s*=\s*[^$<{])' -- ':!scripts/check_repo.sh'; then
  echo "Potential secret found. Review before committing." >&2
  exit 1
fi

echo "Checking required repository structure..."
for path in \
  AGENTS.md \
  README.md \
  AndroidAgent \
  MiddleServer \
  docs/README.md \
  docs/00-product/product-overview.md \
  docs/01-architecture/system-architecture.md \
  docs/02-android/android-ui-and-features.md \
  docs/03-agent-system/elder-agent-design.md \
  docs/04-middle-server/fastapi-communication.md \
  docs/07-testing/test-plan.md; do
  test -e "$path" || { echo "Missing $path" >&2; exit 1; }
done

echo "Checking that code directories do not contain duplicate project instructions..."
for path in AndroidAgent/AGENTS.md AndroidAgent/README.md MiddleServer/AGENTS.md MiddleServer/README.md; do
  if test -e "$path"; then
    echo "Duplicate documentation found: $path" >&2
    exit 1
  fi
done

echo "Checking that obsolete top-level directories are absent..."
for path in android server plans infra; do
  if test -e "$path"; then
    echo "Obsolete path found: $path" >&2
    exit 1
  fi
done

echo "Repository checks passed."
