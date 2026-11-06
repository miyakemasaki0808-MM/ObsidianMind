#!/usr/bin/env bash
# コミット前のゲート。成功したときだけ、文書を除く作業ツリーのハッシュを通過の記録として残す。
# 記録は pre_commit_hook.sh が git commit の直前に照らす（→ docs/dev/harness/README.md §1）。
# 引数は Gradle へそのまま足す（androidTest を触ったら assembleDebugAndroidTest）。
set -u

. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
cd "$(git rev-parse --show-toplevel)" || exit 1

record="$(gate_record_path)" || exit 1
# 先に消す。失敗・中断・途中のコード変更では記録を残さない。
rm -f "$record"

before="$(gate_code_tree worktree)" || { echo "gate: 作業ツリーのハッシュを取れなかった" >&2; exit 1; }

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
  export JAVA_HOME="/Applications/AIセット/Android Studio.app/Contents/jbr/Contents/Home"
fi
./gradlew testDebugUnitTest lintDebug --offline "$@"
status=$?
if [ $status -ne 0 ]; then
  echo "gate: 失敗した（終了コード ${status}）。通過の記録は残していない" >&2
  exit $status
fi

after="$(gate_code_tree worktree)" || { echo "gate: 作業ツリーのハッシュを取れなかった" >&2; exit 1; }
if [ "$before" != "$after" ]; then
  echo "gate: 実行中にコードが変わったので、通過の記録は残していない。もう一度流す" >&2
  exit 1
fi

printf '%s\n' "$after" > "$record"
echo "gate: 通過を記録した（コードのツリー ${after}）"
