#!/usr/bin/env python3
"""大きなVaultの性能比較に使う合成fixture（課題の受理後に削除する）。

2026-10-12 の基準HEAD e0e7c52d の観測で使った生成処理から性能用だけを抽出。
本数500/1500/3000、seed=20261012+本数。元Vaultの名前・本文は含まない。
深さ・サイズの分布は下の定数、測定値・測定方法の限界は current_issues.md が持つ。
Python 3 の標準ライブラリだけを使い、未作成の出力先に生成して旧fixtureのSHA-256と照合する。

実行例: python3 docs/_wip/random_scan_fixture.py /tmp/vigilith-scale-comparison
既存の出力先は上書きしない。端末へコピーする操作は含まない。
"""
import argparse
import hashlib
import pathlib
import random

EXPECTED = {
    500: "7c98cd46a01ce56a1c6b9621c9a17a4e2ee94af5cb5eabb5681d441042221d54",
    1500: "e9b04c1ecd2198e718b9b6099df9f36be00b561d0458ce7b5ebec4cc21d5b8ce",
    3000: "c565dba3ab10664ae59b17c7897e94a0e5aeaaed81dac8262b7581217b080609",
}


def front(tags):
    return "---\ntags: [" + ", ".join(tags) + "]\n---\n"


BASE_NOTES = 504
NOTES_BY_DEPTH = {0: 18, 1: 77, 2: 67, 3: 209, 4: 6, 5: 23, 6: 104}  # 深さ0〜6
NOTES_PER_FOLDER = 504 / 114
SIZE_POINTS = [(0.0, 300), (0.1, 1238), (0.5, 3777), (0.9, 17538), (1.0, 94388)]  # バイトの分位点


FILLER = ("考えたことを短く書き留める。あとで読み返すと、そのときの迷いがよく分かる。"
          "同じ問いに何度も戻ってくるのは、まだ答えが決まっていないからだ。")


def sample_size(rng):
    """分位点のあいだを対数で線形に補う。"""
    import math
    u = rng.random()
    for (p0, s0), (p1, s1) in zip(SIZE_POINTS, SIZE_POINTS[1:]):
        if u <= p1:
            t = (u - p0) / (p1 - p0)
            return int(math.exp(math.log(s0) + t * (math.log(s1) - math.log(s0))))
    return SIZE_POINTS[-1][1]


def synthetic_note(index, size, rng):
    head = front(["合成", f"群{index % 12:02d}"]) + f"# 合成ノート {index:05d}\n\n"
    parts = [head]
    n = 0
    while len("".join(parts).encode("utf-8")) < size:
        n += 1
        if n % 6 == 1:
            parts.append(f"## 節{n // 6 + 1}\n\n")
        parts.append(f"{FILLER}（{index:05d}-{n}）\n\n")
    return "".join(parts)


def build_scale(dest, total, seed):
    rng = random.Random(seed)
    k = total / BASE_NOTES
    notes = {d: round(c * k) for d, c in NOTES_BY_DEPTH.items()}
    notes[3] += total - sum(notes.values())  # 丸めの差は最多の深さで吸収する
    folders = {0: [pathlib.Path(".")]}
    for d in range(1, max(NOTES_BY_DEPTH) + 1):
        count = max(1, round(notes[d] / NOTES_PER_FOLDER))
        folders[d] = [rng.choice(folders[d - 1]) / f"フォルダ{d}-{i:03d}" for i in range(1, count + 1)]
    index = 0
    for d, count in notes.items():
        for _ in range(count):
            index += 1
            rel = rng.choice(folders[d]) / f"合成ノート {index:05d}.md"
            write(dest / rel, synthetic_note(index, sample_size(rng), rng))
    for d in range(1, max(NOTES_BY_DEPTH) + 1):  # ノートの無いフォルダも作る（走査はフォルダごとに IPC が要る）
        for f in folders[d]:
            (dest / f).mkdir(parents=True, exist_ok=True)
    return notes, {d: len(v) for d, v in folders.items() if d > 0}


def mkparent(path):
    path.parent.mkdir(parents=True, exist_ok=True)
    return path


def write(path, text):
    mkparent(path).write_text(text, encoding="utf-8")


def tree_digest(root):
    """相対パス順で D\tパス\n / F\tパス\t内容SHA-256\n を束ね、空フォルダも照合する。"""
    lines = []
    for path in sorted(root.rglob("*"), key=lambda p: p.relative_to(root).as_posix()):
        relative = path.relative_to(root).as_posix()
        if path.is_dir():
            lines.append(f"D\t{relative}\n")
        else:
            sha = hashlib.sha256(path.read_bytes()).hexdigest()
            lines.append(f"F\t{relative}\t{sha}\n")
    return hashlib.sha256("".join(lines).encode("utf-8")).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=pathlib.Path, help="未作成の出力先")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    for total, expected in EXPECTED.items():
        dest = args.output / f"observation_scale{total}"
        build_scale(dest, total, seed=20261012 + total)
        actual = tree_digest(dest)
        if actual != expected:
            raise RuntimeError(f"{total}: SHA-256 mismatch: {actual}")
        print(f"{total}: SHA-256一致 {actual}")


if __name__ == "__main__":
    main()
