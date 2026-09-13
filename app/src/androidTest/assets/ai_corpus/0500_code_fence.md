# バックアップのコマンド

外付けSSDへの手動バックアップ。月末に実行する。

## 確認

接続されているかを先に見る。

```bash
ls /Volumes/
df -h /Volumes/BackupSSD
```

空き容量が50GB を切っていたら古い世代を消してから実行する。

## 実行

```bash
rsync -av --delete \
  --exclude='.DS_Store' \
  --exclude='node_modules' \
  --exclude='.cache' \
  ~/Documents/ /Volumes/BackupSSD/Documents/
```

`--delete` は元で消したファイルを先でも消す。
**これを付けないと消したはずのものが復活し続ける。**

## 世代を残す

```bash
DATE=$(date +%Y%m%d)
cp -a /Volumes/BackupSSD/Documents "/Volumes/BackupSSD/snapshots/$DATE"
```

3世代まで。それ以上は手で消す。

## 復元の確認

年に1回、適当なファイルを別の場所へ戻して開けることを確かめる。
取ったことがあるだけのバックアップは、取っていないのとあまり変わらない。
