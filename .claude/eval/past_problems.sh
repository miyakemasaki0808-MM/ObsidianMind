#!/usr/bin/env bash
# 過去の修正を過去問にして Claude Code に解かせ、その修正で足したテストで合否を出す（→ docs/dev/harness/README.md §4）。
#
#   .claude/eval/past_problems.sh check <問題ID...>          問題が成り立つか（親で既存のテストが通り、隠しテストは親で落ちて修正で通る）。トークンを使わない
#   .claude/eval/past_problems.sh run <条件> <問題ID...>     解かせて採点する。条件は as-is（親のまま）か no-claude-md（CLAUDE.md を読ませない）
#
# 問題は problems.tsv（ID・修正のコミット）と problems/<ID>.md（課題文）。
# 解かせる場所は、親コミットとその祖先だけを持つ使い捨てのリポジトリ。後の履歴も本体の場所も持たないので、
# 修正そのものを読んで解くことができない。隠しテストは、解き終えた後にだけ修正のコミットから書き戻す。
#
# 環境変数: EVAL_TOKEN_CAP（1回の起動で使うトークンの上限。超えたら次の問題へ進まない。既定 2000000）、
#           EVAL_MODEL（既定 claude-opus-5-5）、EVAL_TIMEOUT（1問の秒数の上限。既定 1800）、
#           EVAL_CLAUDE（claude の実行ファイル。既定はアプリが同梱する最新のもの）、EVAL_KEEP=1（解いた場所を残す）
set -u

readonly JBR="/Applications/AIセット/Android Studio.app/Contents/jbr/Contents/Home"
readonly TOKEN_CAP="${EVAL_TOKEN_CAP:-2000000}"
readonly MODEL="${EVAL_MODEL:-claude-opus-5-5}"
readonly SOLVE_TIMEOUT="${EVAL_TIMEOUT:-1800}"

usage() {
  echo "使い方: $0 check <問題ID...> | $0 run <as-is|no-claude-md> <問題ID...>" >&2
  exit 64
}

root="$(git rev-parse --show-toplevel)" || exit 1
eval_dir="${root}/.claude/eval"
state_dir="$(git -C "$root" rev-parse --path-format=absolute --git-common-dir)/vigilith-eval"
mkdir -p "$state_dir" || exit 1
results="${state_dir}/results.tsv"

fix_of() {
  awk -F'\t' -v id="$1" '$1 == id { print $2 }' "${eval_dir}/problems.tsv"
}

# 修正で足した・変えた JVM テストのファイル。これが隠しテストになる。
hidden_tests_of() {
  git -C "$root" diff-tree --no-commit-id --name-only -r --diff-filter=AM "$1" -- app/src/test/
}

# コミットとその祖先だけを持つ使い捨てのリポジトリを作る。後の履歴（修正そのもの）は取り込まない。
# 履歴を持たせるのは、設計文書が名指すコミットの実在を確かめるテストがあり、履歴が無いと解く前から落ちるため。
# local.properties は追跡外なので写す。過去問の一覧と課題文（.claude/eval）は、ほかの問題の修正のコミットを名指すので外す。
snapshot() {
  local commit="$1" dir="$2"
  git init -q "$dir" || return 1
  git -C "$dir" fetch -q --no-tags "$root" "$(git -C "$root" rev-parse "$commit")" || return 1
  git -C "$dir" checkout -q -b eval FETCH_HEAD || return 1
  rm -rf "$dir/.claude/eval"
  cp "${root}/local.properties" "$dir/" || return 1
}

# 解く側が ~/.claude/projects に残す作業の場所ごとの置き場を消す。
forget_project() {
  local encoded
  encoded="$(cd "$1" && pwd -P | sed 's/[^A-Za-z0-9]/-/g')" || return 0
  [ -n "$encoded" ] && rm -rf "$HOME/.claude/projects/${encoded}"
}

# 隠しテストを修正のコミットから書き戻し、そのクラスだけを流す。成功なら 0。
run_hidden_tests() {
  local fix="$1" dir="$2" log="$3" file args=()
  while IFS= read -r file; do
    [ -n "$file" ] || continue
    mkdir -p "$dir/$(dirname "$file")"
    git -C "$root" show "${fix}:${file}" > "$dir/$file" || return 1
    args+=(--tests "*.$(basename "$file" .kt)")
  done < <(hidden_tests_of "$fix")
  [ ${#args[@]} -gt 0 ] || { echo "隠しテストが無い" > "$log"; return 1; }
  (cd "$dir" && JAVA_HOME="$JBR" ./gradlew testDebugUnitTest --offline -q "${args[@]}") > "$log" 2>&1
}

claude_bin() {
  if [ -n "${EVAL_CLAUDE:-}" ]; then
    echo "$EVAL_CLAUDE"
    return
  fi
  ls -t "$HOME/Library/Application Support/Claude/claude-code"/*/*/claude.app/Contents/MacOS/claude 2>/dev/null | head -1
}

check() {
  local id fix work status=0
  for id in "$@"; do
    fix="$(fix_of "$id")"
    [ -n "$fix" ] || { echo "eval: 問題が無い: ${id}" >&2; status=1; continue; }
    work="$(mktemp -d "${TMPDIR:-/tmp}/vigilith-eval.XXXXXX")" || exit 1
    snapshot "${fix}^" "$work/parent" && snapshot "$fix" "$work/fix" || { echo "eval: ${id}: 書き出せない" >&2; status=1; continue; }
    # 解く側が見る親の世界は、隠しテストを置く前に既存のテストがすべて通っていなければならない。
    # 落ちていると、解く側は課題と無関係な失敗を追ってトークンを使う。
    if ! (cd "$work/parent" && JAVA_HOME="$JBR" ./gradlew testDebugUnitTest --offline -q) > "$work/baseline.log" 2>&1; then
      echo "eval: ${id}: 親で既存のテストが落ちる（${work}/baseline.log）"
      status=1
      continue
    fi
    if run_hidden_tests "$fix" "$work/parent" "$work/parent.log"; then
      echo "eval: ${id}: 親で隠しテストが通る（問題になっていない）"
      status=1
    elif ! run_hidden_tests "$fix" "$work/fix" "$work/fix.log"; then
      echo "eval: ${id}: 修正でも隠しテストが通らない（${work}/fix.log）"
      status=1
    else
      echo "eval: ${id}: 成り立つ（親で落ち、修正で通る）"
    fi
    [ "${EVAL_KEEP:-0}" = 1 ] || rm -rf "$work"
  done
  return "$status"
}

# 条件ごとに解く側へ足す環境変数。CLAUDE.md はファイルを消さずに読ませない（消すと、それを読む文書の検査が解く前から落ちる）。
condition_env() {
  case "$1" in
    as-is) echo "EVAL_CONDITION=as-is" ;;
    no-claude-md) echo "CLAUDE_CODE_DISABLE_CLAUDE_MDS=1" ;;
    *) return 1 ;;
  esac
}

solve() {
  local condition="$1"; shift
  local bin id fix work prompt start seconds used=0 tokens pass
  local extra_env
  extra_env="$(condition_env "$condition")" || { echo "eval: 知らない条件: ${condition}" >&2; exit 64; }
  bin="$(claude_bin)"
  [ -x "$bin" ] || { echo "eval: claude の実行ファイルが見つからない" >&2; exit 1; }
  if ! env -i HOME="$HOME" PATH="$PATH" USER="${USER:-}" "$bin" auth status --json 2>/dev/null | grep -q '"loggedIn": *true'; then
    echo "eval: 解く側の claude がログインしていない（\"${bin}\" auth login を一度流す）" >&2
    exit 1
  fi
  [ -f "$results" ] || printf 'date\tproblem\tfix\tcondition\tmodel\tpass\tseconds\ttokens\tcost_usd\tturns\tclaude_status\n' > "$results"
  for id in "$@"; do
    if [ "$used" -ge "$TOKEN_CAP" ]; then
      echo "eval: トークンの上限（${TOKEN_CAP}）に達したので、${id} から先は解かせない"
      break
    fi
    fix="$(fix_of "$id")"
    [ -n "$fix" ] || { echo "eval: 問題が無い: ${id}" >&2; continue; }
    work="$(mktemp -d "${TMPDIR:-/tmp}/vigilith-eval.XXXXXX")" || exit 1
    snapshot "${fix}^" "$work/repo" || { echo "eval: ${id}: 書き出せない" >&2; continue; }
    prompt="$(cat "${eval_dir}/problems/${id}.md")

- 質問はできません。判断が要るときは、最も妥当なほうを選んで進めてください。
- コミットはしないでください。
- 終わったら、何を直したかを短く報告してください。"
    start="$(date +%s)"
    # 環境は最小にして渡す。アプリの会話の中から流すと、会話の接続先と認証の変数が子へ漏れ、解く側が会話の一部として動く。
    (
      cd "$work/repo" &&
        env -i HOME="$HOME" PATH="$PATH" USER="${USER:-}" LOGNAME="${LOGNAME:-${USER:-}}" TMPDIR="${TMPDIR:-/tmp}" \
          LANG="${LANG:-ja_JP.UTF-8}" JAVA_HOME="$JBR" CLAUDE_CODE_DISABLE_AUTO_MEMORY=1 "$extra_env" \
          timeout "$SOLVE_TIMEOUT" "$bin" -p "$prompt" \
          --model "$MODEL" \
          --output-format json \
          --permission-mode acceptEdits \
          --allowedTools "Read" "Edit" "Write" "Glob" "Grep" "Bash(./gradlew *)" "Bash(.claude/gate/gate.sh*)" \
          --disallowedTools "Read(/${root}/**)" "Edit(/${root}/**)" \
          --setting-sources project,local \
          --strict-mcp-config \
          --no-session-persistence
    ) > "$work/solve.json" 2> "$work/solve.err"
    local claude_status=$?
    seconds=$(( $(date +%s) - start ))
    forget_project "$work/repo"
    read -r tokens cost turns < <(python3 - "$work/solve.json" <<'PY'
import json, sys
try:
    data = json.load(open(sys.argv[1], encoding="utf-8"))
except Exception:
    print(0, "?", "?")
    sys.exit()
# modelUsage はモデルごとの合計（下位の小さいモデルの分も含む）。無ければ主のモデルの usage を使う。
models = data.get("modelUsage") or {}
if models:
    keys = ("inputTokens", "outputTokens", "cacheCreationInputTokens", "cacheReadInputTokens")
    tokens = sum(int(model.get(key) or 0) for model in models.values() for key in keys)
else:
    usage = data.get("usage") or {}
    keys = ("input_tokens", "output_tokens", "cache_creation_input_tokens", "cache_read_input_tokens")
    tokens = sum(int(usage.get(key) or 0) for key in keys)
print(tokens, data.get("total_cost_usd", "?"), data.get("num_turns", "?"))
PY
)
    used=$(( used + tokens ))
    # 何を触ったか（文書の更新・ゲートの実行など、隠しテストでは測れないもの）を後から読めるよう、隠しテストを置く前に残す。
    local run_dir="${state_dir}/runs/$(date +%Y%m%dT%H%M%S)-${id}-${condition}"
    mkdir -p "$run_dir" &&
      git -C "$work/repo" status --short -- . ':(exclude).claude/eval' > "$run_dir/status.txt" &&
      git -C "$work/repo" add -A -- . ':(exclude).claude/eval' &&
      git -C "$work/repo" diff --cached > "$run_dir/diff.patch" &&
      cp "$work/solve.json" "$work/solve.err" "$run_dir/"
    if run_hidden_tests "$fix" "$work/repo" "$work/hidden.log"; then pass=1; else pass=0; fi
    cp "$work/hidden.log" "$run_dir/" 2>/dev/null
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$(date +%FT%T)" "$id" "$fix" "$condition" "$MODEL" "$pass" "$seconds" "$tokens" "$cost" "$turns" "$claude_status" >> "$results"
    echo "eval: ${id}（${condition}）: $([ "$pass" = 1 ] && echo 合格 || echo 不合格)・${seconds}秒・${tokens}トークン（累計 ${used}）"
    if [ "${EVAL_KEEP:-0}" = 1 ]; then echo "eval: 解いた場所: ${work}"; else rm -rf "$work"; fi
  done
}

[ $# -ge 2 ] || usage
case "$1" in
  check) shift; check "$@" ;;
  run) shift; solve "$@" ;;
  *) usage ;;
esac
