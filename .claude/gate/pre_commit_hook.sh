#!/usr/bin/env bash
# Claude Code の PreToolUse（Bash）。git commit を含むコマンドを、ゲートの通過記録と照らしてから通す。
# 止めるときは終了コード 2 と標準エラーで理由を Claude へ返す（→ docs/dev/harness/README.md §1）。
#
# コマンドを走らせる前に照らすので、ゲートとコミットを ; や && でつないでも止まる。
# 文書だけの変更は止めない。照らすのはコミットされる差分ではなく作業ツリー全体 —
# 同じコマンドの中で git add するとフックの時点では何が入るか分からないため、広い側に倒している。
set -u

. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

input="$(cat)"

if command -v jq >/dev/null 2>&1; then
  command_text="$(printf '%s' "$input" | jq -r '.tool_input.command // empty')" || command_text="$input"
  cwd="$(printf '%s' "$input" | jq -r '.cwd // empty')" || cwd=""
else
  # 読めないときは入力全体を照らす。止める側に倒れるが、黙ってすり抜けるよりよい。
  command_text="$input"
  cwd=""
fi

# git のあと、オプション（-C 道・-c 鍵=値・--何か）だけを挟んで commit が来る形。
# commit-tree や git log --grep commit は当たらない。
commit_pattern='(^|[;&|({!`[:space:]])git([[:space:]]+-[Cc][[:space:]]+[^[:space:];&|]+|[[:space:]]+--?[A-Za-z][^[:space:];&|]*)*[[:space:]]+commit([[:space:];&|)]|$)'
[[ "$command_text" =~ $commit_pattern ]] || exit 0

for dir in "$cwd" "${CLAUDE_PROJECT_DIR:-}"; do
  if [ -n "$dir" ] && [ -d "$dir" ]; then cd "$dir" && break; fi
done
git rev-parse --show-toplevel >/dev/null 2>&1 || exit 0

code="$(gate_code_tree worktree)" || { echo "コミット前のゲート: 作業ツリーのハッシュを取れなかったので止めた。" >&2; exit 2; }
head="$(gate_code_tree HEAD)" || { echo "コミット前のゲート: HEAD のハッシュを取れなかったので止めた。" >&2; exit 2; }

# 文書だけの変更（またはコードに変更なし）は通す。
[ "$code" = "$head" ] && exit 0

record="$(gate_record_path)"
recorded="$(cat "$record" 2>/dev/null || true)"
[ "$recorded" = "$code" ] && exit 0

if [ -z "$recorded" ]; then
  reason="ゲートの通過記録が無い（まだ流していないか、前回失敗した）。"
else
  reason="ゲートを通した後にコードが変わっている。"
fi
cat >&2 <<EOF
コミット前のゲート: このコミットを止めた。${reason}
.claude/gate/gate.sh を単独のコマンドで流し、成功を確かめてからコミットする。
ゲートとコミットを ; や && でつながない（→ docs/dev/harness/README.md §1）。
EOF
exit 2
