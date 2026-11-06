#!/usr/bin/env bash
# Claude Code の PreToolUse（Bash）。git commit を含むコマンドを、ゲートの通過記録と照らしてから通す。
# 止めるときは終了コード 2 と標準エラーで理由を Claude へ返す（→ docs/dev/harness/README.md §1）。
#
# コマンドを走らせる前に照らすので、ゲートとコミットを ; や && でつないでも止まる。
# 文書だけの変更は止めない。照らすのは作業ツリー全体と索引の両方 — ゲートが見たのは作業ツリーなので、
# 索引がそれと違えば止める。同じコマンドの中の git add はフックの時点で済んでいないので、それでも止まる。
# 照らすのはコミットする worktree。git -C は解いてその worktree で照らし、決められない形は止める。
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

# git のあと、オプションだけを挟んで commit が来る形。commit-tree や git log --grep commit は当たらない。
# 引数を別の語で取るオプション（-C 道・-c 鍵=値・--git-dir 道など）は、引用符で囲んだ空白入りの引数も1語と読む。
# 読めない形を当たらないまま通すと、そのコミットは照らされずに通る。
q="'"
arg="(\"[^\"]*\"|${q}[^${q}]*${q}|[^[:space:];&|\"${q}]+)"
opt_arg="(-C|-c|--git-dir|--work-tree|--namespace|--config-env|--super-prefix)[[:space:]]+${arg}"
opt_plain="--?[A-Za-z][^[:space:];&|\"${q}=]*(=${arg})?"
lead="(^|[;&|({!\`\"${q}[:space:]])"
commit_pattern="${lead}git(([[:space:]]+(${opt_arg}|${opt_plain}))*)[[:space:]]+commit([[:space:];&|)\"${q}\`]|\$)"
token_pattern="^[[:space:]]+(${arg})(.*)\$"
# コミットより前にあると、コミットする worktree や索引が作業ディレクトリと変わる形。
dir_change_pattern="${lead}(cd|pushd|popd)([[:space:];&|)]|\$)"
git_env_pattern="GIT_(DIR|WORK_TREE|INDEX_FILE|COMMON_DIR)="

refuse() {
  cat >&2 <<EOF
コミット前のゲート: このコミットを止めた。コミットする worktree をコマンドから決められない（$1）。
対象の worktree を作業ディレクトリにして、cd や GIT_DIR を使わずに git commit を単独のコマンドで流す（→ docs/dev/harness/README.md §1）。
EOF
  exit 2
}

base=""
for dir in "$cwd" "${CLAUDE_PROJECT_DIR:-}"; do
  if [ -n "$dir" ] && [ -d "$dir" ]; then base="$dir"; break; fi
done
[ -n "$base" ] || base="$PWD"

# コマンドの中のコミットごとに、コミットする worktree を解く。
targets=()
seen=""
rest="$command_text"
while [[ "$rest" =~ $commit_pattern ]]; do
  match="${BASH_REMATCH[0]}"
  opts="${BASH_REMATCH[2]}"
  seen="${seen}${rest%%"$match"*}"
  [[ "$seen" =~ $dir_change_pattern ]] && refuse "コミットの前に cd などで作業ディレクトリを変えている"
  [[ "$seen" =~ $git_env_pattern ]] && refuse "GIT_DIR などの環境変数で対象を変えている"

  target="$base"
  expect_dir=0
  skip_next=0
  while [[ "$opts" =~ $token_pattern ]]; do
    tok="${BASH_REMATCH[1]}"
    opts="${BASH_REMATCH[3]}"
    if [ $expect_dir -eq 1 ]; then
      expect_dir=0
      case "$tok" in
        *'$'*|*'`'*|*\\*|\~*) refuse "-C の行き先に変数や展開が入っている: ${tok}" ;;
        \"*\"|\'*\') tok="${tok:1:${#tok}-2}" ;;
      esac
      case "$tok" in
        /*) target="$tok" ;;
        *) target="${target}/${tok}" ;;
      esac
    elif [ $skip_next -eq 1 ]; then
      skip_next=0
    else
      case "$tok" in
        -C) expect_dir=1 ;;
        --git-dir|--git-dir=*|--work-tree|--work-tree=*) refuse "--git-dir や --work-tree で対象を変えている" ;;
        -c|--namespace|--config-env|--super-prefix) skip_next=1 ;;
      esac
    fi
  done
  targets+=("$target")
  seen="${seen}${match}"
  rest="${rest#*"$match"}"
done
[ ${#targets[@]} -gt 0 ] || exit 0

# 今の作業ディレクトリの worktree を照らす。止めるときは理由を標準エラーへ書いて 2 を返す。
check_here() {
  local top code index head record recorded reason
  top="$(git rev-parse --show-toplevel 2>/dev/null)" || return 0

  code="$(gate_code_tree worktree)" || { echo "コミット前のゲート: 作業ツリーのハッシュを取れなかったので止めた（${top}）。" >&2; return 2; }
  index="$(gate_code_tree index)" || { echo "コミット前のゲート: 索引のハッシュを取れなかったので止めた（${top}。衝突の解消中なら、先に git add で解消する）。" >&2; return 2; }
  head="$(gate_code_tree HEAD)" || { echo "コミット前のゲート: HEAD のハッシュを取れなかったので止めた（${top}）。" >&2; return 2; }

  # 文書だけの変更（またはコードに変更なし）は通す。作業ツリーと索引の両方が HEAD と同じコードのとき。
  [ "$code" = "$head" ] && [ "$index" = "$head" ] && return 0

  if [ "$code" != "$head" ]; then
    record="$(gate_record_path)"
    recorded="$(cat "$record" 2>/dev/null || true)"
    if [ "$recorded" != "$code" ]; then
      if [ -z "$recorded" ]; then
        reason="ゲートの通過記録が無い（まだ流していないか、前回失敗した）。"
      else
        reason="ゲートを通した後にコードが変わっている。"
      fi
      cat >&2 <<EOF
コミット前のゲート: このコミットを止めた。${reason}
対象の worktree: ${top}
.claude/gate/gate.sh をその worktree で単独のコマンドとして流し、成功を確かめてからコミットする。
ゲートとコミットを ; や && でつながない（→ docs/dev/harness/README.md §1）。
EOF
      return 2
    fi
  fi

  # ゲートが見たのは作業ツリーなので、コミットに入る索引もそれと揃っていなければならない。
  if [ "$index" != "$code" ]; then
    cat >&2 <<EOF
コミット前のゲート: このコミットを止めた。索引のコードが作業ツリーと違う
（ステージしていないコードの変更・未追跡のコードがあるか、作業ファイルと違う内容をステージしている）。
対象の worktree: ${top}
git add を単独のコマンドで流して索引を作業ツリーへ揃えてから、コミットする。
コミットに入れないコードの変更は退避する（→ docs/dev/harness/README.md §1）。
EOF
    return 2
  fi
  return 0
}

status=0
for target in "${targets[@]}"; do
  if ! cd "$target" 2>/dev/null; then
    echo "コミット前のゲート: コミットする worktree へ移れないので止めた（${target}）。" >&2
    status=2
    continue
  fi
  check_here || status=2
done
exit $status
