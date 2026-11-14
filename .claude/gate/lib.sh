# コミット前のゲートの共有部分。gate.sh と Git の pre-commit（githooks/pre-commit）が読み込む（→ docs/dev/harness/README.md §1）。
#
# 「コード」は追跡対象と未追跡（.gitignore の外）のファイルから、文書を除いたもの。
# 文書は docs/・最上位の *.md・.claude/skills/ で、ゲートの後に足しても記録を無効にしない。
# 除く範囲を広げると、ゲートを通していないコードが文書の顔をして通る。

# git のパス指定。最上位からの相対で解釈させる（呼び出し元の cwd に依らない）。
GATE_DOC_PATHSPECS=(':(top)docs' ':(top,glob)*.md' ':(top).claude/skills')

# 通過の記録の置き場。git ディレクトリの中なので追跡されず、worktree ごとに分かれる。
gate_record_path() {
  git rev-parse --path-format=absolute --git-path vigilith-gate-pass
}

# 一時の索引でツリーを組み、文書を抜いて書き出す。$1 が worktree なら作業ツリー、index ならコミットに入る索引、
# HEAD なら最新のコミット。本物の索引は触らない — 一時の索引に写して使う（写すのは stat の控えを再利用して速くするため）。
# worktree は写した索引へ作業ツリーを重ねるので、作業ファイルと違うステージの内容は見えない。索引は index で別に取る。
# --git-path index は GIT_INDEX_FILE に従うので、Git のフックの中では commit -a やパス指定の一時の索引を読む。
gate_code_tree() {
  local source="$1" tmp real rc=0
  tmp="$(git rev-parse --path-format=absolute --git-path "vigilith-gate-index.$$")" || return 1
  rm -f "$tmp"
  real="$(git rev-parse --path-format=absolute --git-path index)" || return 1
  if [ "$source" = "worktree" ]; then
    [ -f "$real" ] && cp "$real" "$tmp"
    GIT_INDEX_FILE="$tmp" git add -A -- ':/' || rc=1
  elif [ "$source" = "index" ]; then
    if [ -f "$real" ]; then
      cp "$real" "$tmp" || rc=1
    else
      GIT_INDEX_FILE="$tmp" git read-tree --empty || rc=1
    fi
  elif git rev-parse -q --verify HEAD >/dev/null; then
    GIT_INDEX_FILE="$tmp" git read-tree HEAD || rc=1
  else
    GIT_INDEX_FILE="$tmp" git read-tree --empty || rc=1
  fi
  if [ $rc -eq 0 ]; then
    GIT_INDEX_FILE="$tmp" git rm -r -q --cached --ignore-unmatch -- "${GATE_DOC_PATHSPECS[@]}" >/dev/null || rc=1
  fi
  if [ $rc -eq 0 ]; then
    GIT_INDEX_FILE="$tmp" git write-tree || rc=1
  fi
  rm -f "$tmp"
  return $rc
}
