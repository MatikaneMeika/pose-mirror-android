# pose-mirror-android 📱

Android client for **pose-mirror**: strike a pose in front of your phone camera,
get matching reference images for drawing. Fully offline, no account, no cloud.

This is the **mobile half** of the project. It is completely independent from
the PC version ([pose-mirror](https://github.com/MatikaneMeika/pose-mirror)):
no networking between them. The only thing the two apps share is the portable
index format (`docs/INDEX_FORMAT.md` in the PC repo).

[中文说明](#中文)

## Status

Scaffold (v0.1). Camera → pose → search pipeline is wired end to end; UI is
functional but plain. Good first contributions: pose skeleton overlay on the
preview, settings screen, search-history.

## How it works

```
CameraX frame ──▶ MediaPipe PoseLandmarker (33 landmarks)
      ──▶ normalize to 66-dim unit vector (same math as PC, see PoseMath.kt)
      ──▶ cosine search over index vectors (memory-mapped)
      ──▶ thumbnail grid with similarity scores
```

## Build

1. Open this directory in Android Studio (Koala or newer).
2. Download the pose model into the assets folder:
   ```bash
   bash tools/download_model.sh
   # -> app/src/main/assets/pose_landmarker_full.task (~9 MB, git-ignored)
   ```
3. Get an index bundle. On your PC, from the pose-mirror repo:
   ```bash
   python -m posemirror.export_portable --index data/index --out dist/index-v1
   cd dist && zip -r index-v1.zip index-v1
   ```
   Host `index-v1.zip` somewhere your phone can reach (e.g. a GitHub Release).
4. Run the app. On first launch it asks for the index zip URL, downloads and
   unzips it into app storage, then starts matching. The model + index stay on
   device — everything after that is offline.

## Index format

The app reads the **portable bundle** produced by
`python -m posemirror.export_portable` (see `docs/INDEX_FORMAT.md`):

| File | Contents |
|---|---|
| `format.json` | `index_version` (must be `1`), `vector_dim` (`66`) |
| `ids.json` | `["000000", ...]`, aligned with vector rows |
| `vectors.f32le.bin` | raw little-endian float32, row-major, `N*66` |
| `manifest.json` | per-id metadata (title, author, license, source) |
| `thumbs/{id}.jpg` | thumbnails |

Pose normalization is a line-by-line Kotlin port of the PC pseudocode:
mid-hip origin, shoulder–hip scale (fallback: max joint distance), x/y only,
L2-normalized unit vector, cosine similarity, mirror toggle negates the x
components. `PoseMath.kt` is the file to diff against the Python version.

## Layout

```
app/src/main/java/com/posemirror/app/
  MainActivity.kt            CameraX wiring, permissions, UI updates
  pose/
    PoseMath.kt              normalization + search math (pure, portable)
    PoseLandmarkerHelper.kt  MediaPipe Tasks wrapper (GPU w/ CPU fallback)
  index/
    PoseIndex.kt             loads + memory-maps the index bundle, top-k search
    IndexDownloader.kt       downloads + unzips the index bundle on first run
  ui/
    ResultsAdapter.kt        thumbnail grid
```

## License

MIT — see [LICENSE](LICENSE).

---

## 中文

pose-mirror 的**安卓端**：对着手机摄像头摆动作，自动找到姿势最像的参考图，
给画师当姿势参考。完全离线，不注册、不联网。

这是独立 App，和 PC 版（[pose-mirror](https://github.com/MatikaneMeika/pose-mirror)）
之间**没有任何网络连接**，两端唯一的共同点是可移植的索引数据格式
（PC 仓库 `docs/INDEX_FORMAT.md`）。

**构建步骤：** 用 Android Studio 打开本目录；跑
`bash tools/download_model.sh` 下载姿态模型到 assets；在 PC 端用
`python -m posemirror.export_portable` 导出索引包并找个手机能访问的地址放好；
首次启动 App 时填入索引包 URL，下载解压后即可离线使用。
