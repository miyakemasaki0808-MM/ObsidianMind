#!/usr/bin/env bash
# Claude Code の PreToolUse（Bash）。コミットを照らすのは Git の pre-commit（githooks/pre-commit）で、ここはその前提だけを守る
# — Git の pre-commit が入っていること、フックを飛ばす指定（--no-verify・-n・core.hooksPath）が無いこと。
# 止めるときは終了コード 2 と標準エラーで理由を Claude へ返す（→ docs/dev/harness/README.md §1）。
#
# コマンドの文字列を見るので、止めすぎはあっても、どの書き方でコミットされるかを解くことはしない。
# 書き方（別名・関数・git -C・行継続）を問わず実際に入る内容を照らすのは、Git の側である。
set -u

input="$(cat)"

if command -v jq >/dev/null 2>&1; then
  command_text="$(printf '%s' "$input" | jq -r '.tool_input.command // empty')" || command_text="$input"
  cwd="$(printf '%s' "$input" | jq -r '.cwd // empty')" || cwd=""
else
  # 読めないときは入力全体を見る。止める側に倒れるが、黙ってすり抜けるよりよい。
  command_text="$input"
  cwd=""
fi

# 行末のバックスラッシュと改行を取り除いて読む。引用符の中まで取り除くことがあるが、止める側の判定にしか使わない。
continuation=$'\\\n'
text="${command_text//"$continuation"/}"

# commit という語を含まなければ関わらない（commit-tree は別の語として扱う）。
word_commit='(^|[^A-Za-z0-9_-])commit([^A-Za-z0-9_-]|$)'
[[ "$text" =~ $word_commit ]] || exit 0

refuse() {
  cat >&2 <<EOF
コミット前のゲート: このコマンドを止めた。$1
（→ docs/dev/harness/README.md §1）
EOF
  exit 2
}

# フックを飛ばす指定。--no-verify は Git が略記（--no-veri）も受け付ける。-n は commit の短いオプション（-qn のようにまとめても効く）。
# コミットメッセージの中の語にも当たるので、そのときは -F でファイルから渡す。
newline=$'\n'
short_n="commit[^;&|${newline}]*[[:space:]]-[A-Za-z]*n"
if printf '%s' "$text" | grep -qiF -- 'hookspath'; then
  refuse "フックを飛ばす指定（core.hooksPath）を含む。指定を外して流す。メッセージに紛れたなら -F でファイルから渡す。"
fi
if [[ "$text" == *"--no-veri"* ]] || [[ "$text" =~ $short_n ]]; then
  refuse "フックを飛ばす指定（--no-verify・-n）を含む。指定を外して流す。メッセージに紛れたなら -F でファイルから渡す。"
fi

# Git の pre-commit が入っていること。入っていなければ、Git はコミットを照らさずに通す。
base=""
for dir in "$cwd" "${CLAUDE_PROJECT_DIR:-}"; do
  if [ -n "$dir" ] && [ -d "$dir" ]; then base="$dir"; break; fi
done
[ -n "$base" ] || base="$PWD"
top="$(git -C "$base" rev-parse --show-toplevel 2>/dev/null)" || exit 0
hooks="$(git -C "$base" config --get core.hooksPath || true)"
if [ "$hooks" != ".claude/gate/githooks" ] || [ ! -x "${top}/.claude/gate/githooks/pre-commit" ]; then
  refuse "Git の pre-commit が入っていない（core.hooksPath=${hooks:-未設定}）。.claude/gate/gate.sh を流すと入る。"
fi
exit 0
