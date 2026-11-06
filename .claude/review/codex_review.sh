#!/usr/bin/env bash
# Codex に diff を1回レビューさせ、本文を docs/review/ へ置く。往復の手順は Skill review-loop が持つ
# （→ docs/dev/harness/README.md §3）。
#
#   .claude/review/codex_review.sh <series> <base>..<head> [観点のファイル]
#
# 同じ series は最大 3 回まで。P1・P2 が0件になった series は閉じる。どちらも、ここで機械的に断る。
# Codex が追跡しているファイルを変えたら、本文を残したまま失敗で終わる（Codex は製品コードを変えない）。
set -u

readonly MAX_ROUNDS=3

# モデルはオーナーの使い分けに合わせて毎回指定する。~/.codex/config.toml の既定はアプリと共有で、
# 実機検証用（gpt-6.1-sol・中）なので、指定しないとレビューが弱いモデルで走る。
readonly REVIEW_MODEL="gpt-6-astra"
readonly FIRST_EFFORT="xhigh"
readonly RE_REVIEW_EFFORT="high"

usage() {
  echo "使い方: $0 <series> <base>..<head> [観点のファイル]" >&2
  exit 64
}

[ $# -ge 2 ] || usage
series="$1"
range="$2"
focus_file="${3:-}"
[[ "$series" =~ ^[a-z0-9][a-z0-9-]*$ ]] || { echo "review: series は英小文字・数字・- だけで書く: ${series}" >&2; exit 64; }
[[ "$range" == *..* ]] || usage
if [ -n "$focus_file" ] && [ ! -f "$focus_file" ]; then
  echo "review: 観点のファイルが無い: ${focus_file}" >&2
  exit 64
fi

cd "$(git rev-parse --show-toplevel)" || exit 1
git rev-parse -q --verify "${range%%..*}^{commit}" >/dev/null && git rev-parse -q --verify "${range##*..}^{commit}" >/dev/null \
  || { echo "review: 範囲のコミットが無い: ${range}" >&2; exit 64; }

state_dir="$(git rev-parse --path-format=absolute --git-path vigilith-review)"
mkdir -p "$state_dir"
state="${state_dir}/${series}"
rounds=0
closed=0
last_head=""
# 前回の本文のファイル名（.md を除く）。受付IDの接頭辞になるので、頼み直す日の日付から組み直さない。
last_body=""
if [ -f "$state" ]; then
  rounds="$(sed -n 's/^rounds=//p' "$state")"
  closed="$(sed -n 's/^closed=//p' "$state")"
  last_head="$(sed -n 's/^last_head=//p' "$state")"
  last_body="$(sed -n 's/^last_body=//p' "$state")"
fi

if [ "${closed:-0}" = "1" ]; then
  echo "review: ${series} は P1・P2 が0件で閉じている。往復を終えてオーナーへ渡す" >&2
  exit 3
fi
if [ "${rounds:-0}" -ge "$MAX_ROUNDS" ]; then
  echo "review: ${series} は上限の ${MAX_ROUNDS} 回に達した。これ以上は頼まず、受け付けた指摘を添えてオーナーへ渡す" >&2
  exit 3
fi

shopt -s nullglob
existing=(docs/review/[0-9][0-9][0-9][0-9]-*.md)
shopt -u nullglob
if [ ${#existing[@]} -gt 0 ]; then
  echo "review: 前の本文が残っている（${existing[*]}）。先に受け付けて転記し、消す（Skill review-intake）" >&2
  exit 3
fi
if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "review: 追跡しているファイルに未コミットの変更がある。レビューはコミットした状態で頼む" >&2
  exit 3
fi

round=$((rounds + 1))
effort="$RE_REVIEW_EFFORT"
[ "$round" -eq 1 ] && effort="$FIRST_EFFORT"
head="$(git rev-parse --short HEAD)"
branch="$(git branch --show-current)"
body="docs/review/$(date +%F)-${series}-r${round}.md"
log_dir="$(mktemp -d "${TMPDIR:-/tmp}/vigilith-review.XXXXXX")" || exit 1
prompt="${log_dir}/prompt.md"

{
  echo "あなたは Vigilith AI（Android / Kotlin / Jetpack Compose）の、別の目のレビュアーです。"
  echo "実装したのは Claude で、あなたの判定がこの変更の「別の目」になります。"
  echo
  echo "- 対象の範囲: \`${range}\`（基準HEAD \`${head}\`、ブランチ \`${branch}\`）"
  echo "- このシリーズ（${series}）の ${round} 回目のレビューです。上限は ${MAX_ROUNDS} 回です。"
  if [ "$round" -gt 1 ] && [ -n "$last_head" ]; then
    echo "- 前回は \`${last_head}\` までを見ました。前回の本文 \`${last_body}.md\` は受付を済ませて消してあります。"
    echo "  前回の指摘は docs/review/findings.md の受付簿で、ID が \`${last_body}/\` で始まる行と、"
    echo "  そこから引ける docs/_wip/current_issues.md の課題です。"
    echo "  \`${last_head}..${head}\` の修正で、前回の指摘を再現する順序が閉じたかを §1 で判定してください。"
  fi
  echo
  echo "## 守ること"
  echo
  echo "- **追跡しているファイル（製品コード・テスト・文書）を変更しない。** 確かめるための一時のテストは書いてよいが、終わる前に必ず消す。"
  echo "- docs/review/ にファイルを作らない。**レビュー本文は最後のメッセージとして、前置きなしで返す。** 保存はこちらで行う。"
  echo "- 規約は CLAUDE.md、横断規約は docs/dev/system/architecture.md、対象機能の正本は docs/dev/document_map.md §5 の逆引きで引く。"
  echo "- 机上ゲートは Claude が通している。自分で確かめるなら次を使う:"
  echo "  \`export JAVA_HOME='/Applications/AIセット/Android Studio.app/Contents/jbr/Contents/Home' && ./gradlew testDebugUnitTest lintDebug --offline\`"
  echo "- 本文は docs/review/review_template.md の「ひな形」に従う。指摘の見出しは \`### P1-1.\` の形で、受理条件を必ず書き、末尾に「課題起票」節を置く。"
  echo "- 見出しの数字は重さを表す。P1 はデータの損失・事実に反する表示・クラッシュ、P2 は正本との食い違いや実害のありうる契約・検査の穴、P3 は文書や小さな改善。"
  echo "- 同じ指摘を言い換えて繰り返さない。前回から判定が変わらない指摘は §1 に書き、§2 に再掲しない。"
  if [ -n "$focus_file" ]; then
    echo
    echo "## 実装した側が特に見てほしい観点"
    echo
    cat "$focus_file"
  fi
} > "$prompt"

untracked_before="$(git ls-files --others --exclude-standard | sort)"
head_before="$(git rev-parse HEAD)"
start="$(date +%s)"
codex exec \
  -m "$REVIEW_MODEL" \
  -c "model_reasoning_effort=${effort}" \
  -s workspace-write \
  -c 'sandbox_workspace_write.network_access=true' \
  --add-dir "$HOME/.gradle" \
  --add-dir "$HOME/.android" \
  --ephemeral \
  --color never \
  -C "$PWD" \
  -o "$body" \
  - < "$prompt" > "${log_dir}/codex.log" 2>&1
codex_status=$?
seconds=$(( $(date +%s) - start ))
tokens="$(grep -A1 '^tokens used' "${log_dir}/codex.log" | tail -1 | tr -d ', ')"

# Codex が追跡しているファイルを変えたか。コミットや checkout で HEAD が動いた場合も同じに扱う。
tampered="$(git status --porcelain --untracked-files=no)"
head_after="$(git rev-parse HEAD)"
if [ "$head_after" != "$head_before" ]; then
  tampered="${tampered}${tampered:+$'\n'}HEAD が ${head_before} から ${head_after} へ動いた"
fi
untracked_after="$(git ls-files --others --exclude-standard | grep -v -x -F "$body" | sort)"
leftover="$(comm -13 <(printf '%s\n' "$untracked_before") <(printf '%s\n' "$untracked_after") | sed '/^$/d')"

count() { grep -c -E "^### $1-[0-9]+" "$body" 2>/dev/null || true; }
p1=0; p2=0; p3=0
if [ -s "$body" ]; then p1="$(count P1)"; p2="$(count P2)"; p3="$(count P3)"; fi

printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
  "$(date +%FT%T)" "$series" "$round" "$range" "$REVIEW_MODEL" "$effort" "$seconds" "${tokens:-?}" "$p1" "$p2" "$p3" "$codex_status" \
  >> "${state_dir}/log.tsv"

if [ -n "$tampered" ] && { [ "$codex_status" -ne 0 ] || [ ! -s "$body" ]; }; then
  echo "review: Codex が失敗し、追跡しているファイルも変えた。直さずに止まり、オーナーへ渡す:" >&2
  echo "$tampered" >&2
  exit 5
fi
if [ "$codex_status" -ne 0 ] || [ ! -s "$body" ]; then
  echo "review: Codex が失敗した（終了コード ${codex_status}）。回数には数えない。ログ: ${log_dir}/codex.log" >&2
  rm -f "$body"
  exit 2
fi

new_closed=0
[ "$p1" -eq 0 ] && [ "$p2" -eq 0 ] && new_closed=1
printf 'rounds=%s\nclosed=%s\nlast_head=%s\nlast_body=%s\n' "$round" "$new_closed" "$head" "$(basename "$body" .md)" > "$state"

echo "review: ${body}（${REVIEW_MODEL}・推論 ${effort}・${seconds}秒・${tokens:-?}トークン・P1 ${p1}件・P2 ${p2}件・P3 ${p3}件）"

if [ -n "$tampered" ]; then
  echo "review: Codex が追跡しているファイルを変えた。直さずに止まり、オーナーへ渡す:" >&2
  echo "$tampered" >&2
  exit 5
fi
if [ -n "$leftover" ]; then
  echo "review: Codex が未追跡のファイルを残した。中身を見てから消す:" >&2
  echo "$leftover" >&2
fi

if [ "$new_closed" -eq 1 ]; then
  echo "review: P1・P2 は0件。受け付けたら往復を終えて、オーナーへ渡す"
elif [ "$round" -ge "$MAX_ROUNDS" ]; then
  echo "review: 上限の ${MAX_ROUNDS} 回目で P1・P2 が残った。受け付けたら直さずにオーナーへ渡す"
else
  echo "review: P1・P2 が残った。受け付けて直し、次の回を頼む（残り $((MAX_ROUNDS - round)) 回）"
fi
