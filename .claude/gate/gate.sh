#!/usr/bin/env bash
# コミット前のゲート。成功したときだけ、文書を除く作業ツリーのハッシュを通過の記録として残す。
# 記録は Git の pre-commit（githooks/pre-commit）が、コミットされる内容と照らす（→ docs/dev/harness/README.md §1）。
# 引数は Gradle へそのまま足す（androidTest を触ったら assembleDebugAndroidTest）。
set -u

. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
cd "$(git rev-parse --show-toplevel)" || exit 1

# Git の pre-commit を入れる。設定はリポジトリのローカル設定で、worktree の間で共有される。
# core.hooksPath は探索先の置き換えなので、入れると既定の場所（共有の .git/hooks）のフックはすべて走らなくなる。
# 既定の場所に有効なフックがあるか、別の値が入っていれば、オーナーのフックかもしれないので入れない。
hooks_path=".claude/gate/githooks"
current_hooks="$(git config --get core.hooksPath || true)"
if [ -z "$current_hooks" ]; then
  default_hooks="$(git rev-parse --path-format=absolute --git-path hooks)" || exit 1
  active_hooks=""
  for hook in "$default_hooks"/*; do
    [ -f "$hook" ] && [ -x "$hook" ] || continue
    case "$hook" in
      *.sample) ;;
      *) active_hooks="${active_hooks}${active_hooks:+ }$(basename "$hook")" ;;
    esac
  done
  if [ -n "$active_hooks" ]; then
    echo "gate: 既定のフックの場所（${default_hooks}）に有効なフック（${active_hooks}）がある。入れるとそれが走らなくなるので、Git の pre-commit を入れない。オーナーに確かめる" >&2
    exit 1
  fi
  git config core.hooksPath "$hooks_path" || exit 1
  echo "gate: Git の pre-commit を入れた（core.hooksPath=${hooks_path}）"
elif [ "$current_hooks" != "$hooks_path" ]; then
  echo "gate: core.hooksPath が別の値（${current_hooks}）なので、Git の pre-commit を入れられない。オーナーに確かめる" >&2
  exit 1
fi

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
