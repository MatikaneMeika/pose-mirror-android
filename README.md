# pose-mirror-android 📱

Android client for **pose-mirror**: strike a pose in front of your phone camera,
get matching reference images for drawing. Fully offline, no account, no cloud.

This is the **mobile half** of the project. It is completely independent from
the PC version ([pose-mirror](https://github.com/MatikaneMeika/pose-mirror)):
no networking between them. The only thing the two apps share is the portable
index format (`docs/INDEX_FORMAT.md` in the PC repo).

[中文说明](#中文)

## Status

v0.2. Install-and-go: the starter index auto-downloads on first launch, the
pose model ships in the APK (or downloads once at runtime as a fallback), and
a background worker keeps the gallery rolling with fresh images. Search itself
stays fully offline. Good first contributions: pose skeleton overlay on the
preview, search history.

## How it works

```
CameraX frame ──▶ MediaPipe PoseLandmarker (33 landmarks)
      ──▶ normalize to 66-dim unit vector (same math as PC, see PoseMath.kt)
      ──▶ cosine search over index vectors (memory-mapped)
      ──▶ thumbnail grid with similarity scores
```

Search is always local and instant. Separately, a WorkManager job
periodically pulls fresh human-pose images from Wikimedia Commons, runs the
pose model over them **on-device**, and merges the ones with a usable pose
into the local index — then a sweeper deletes expired/over-cap images
(favorites are never deleted).

## Build

1. Open this directory in Android Studio (Koala or newer), or let the
   [GitHub Actions workflow](.github/workflows/android.yml) build it.
2. The pose model is fetched automatically: the Gradle `preBuild` task runs
   `tools/download_model.sh` (best effort), and CI always runs it before
   `assembleDebug`. The ~9 MB model file stays git-ignored. If the APK was
   built without it, the app downloads the model once at runtime into its
   private files dir and memory-maps it — either way, first launch just works.
3. Get an index bundle. On your PC, from the pose-mirror repo:
   ```bash
   python -m posemirror.export_portable --index data/index --out dist/index-v1
   cd dist && zip -r index-v1.zip index-v1
   ```
   Host `index-v1.zip` somewhere your phone can reach (e.g. a GitHub Release).
4. Run the app. On first launch the starter index downloads automatically
   from the release URL in `IndexDownloader.DEFAULT_INDEX_URL` (with a
   progress UI). Only if that fails does the manual URL input appear.

> ⚠️ The starter bundle release (`index-v1/index-v1.zip`) has not been
> uploaded yet — before shipping, publish it to this repo's releases or the
> first launch will fall back to the manual URL input.

## Rolling gallery

On first launch a one-time dialog collects five preset groups (defaults
pre-selected, one tap confirms):

| Setting | Presets | Default |
|---|---|---|
| 更新频率 update frequency | 每天 / 每3天 / 每周 / 关闭 | 每3天 |
| 每次新增图片 batch size | 20 / 50 / 100 | 50 |
| 未收藏图片自动删除 TTL | 7 / 30 / 90 天 | 30 天 |
| 图库数量上限 index cap | 500 / 2000 / 5000 | 2000 |
| 更新网络条件 network | 仅 WiFi / WiFi + 充电时 | 仅 WiFi |

The worker queries Wikimedia Commons for pose-heavy themes (dance, yoga,
sports…), skips any source URL already seen (tracked in the `index_meta.json`
sidecar, which survives purges), and keeps at most the chosen batch size of
**pose-valid** images per run. Long-press a thumbnail to ★ favorite it —
favorites are pinned forever and skipped by the TTL/cap sweeper, which also
runs on every app start.

All numbers and timings come from the user's presets — the app invents none.

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
  MainActivity.kt            CameraX wiring, permissions, auto-download, favorites
  pose/
    PoseMath.kt              normalization + search math (pure, portable)
    PoseLandmarkerHelper.kt  MediaPipe Tasks wrapper, live stream (GPU w/ CPU fallback)
    PoseModelProvider.kt     model resolution: APK asset → cache → one-time download
    PoseBatchDetector.kt     synchronous IMAGE-mode detector for the worker
  index/
    PoseIndex.kt             loads + memory-maps the index bundle, top-k search
    IndexDownloader.kt       first-run starter-index download (+ manual fallback)
    IndexManager.kt          append / purge / pin + index_meta.json sidecar
    IndexSweeper.kt          TTL + cap retention (favorites exempt)
  work/
    UpdateWorker.kt          Wikimedia Commons fetch + on-device pose filter + merge
    UpdateScheduler.kt       unique periodic work from user presets
  prefs/
    AppPrefs.kt              DataStore: five preset groups + first-run flags
  ui/
    ResultsAdapter.kt        thumbnail grid (long-press to ★ favorite)
    PrefsForm.kt             the five preset groups (shared by dialog + settings)
    FirstLaunchDialog.kt     one-time preset picker on first launch
    SettingsActivity.kt      settings screen with live index stats
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

**开箱即用：** 首次启动自动从本仓库 Release 下载 starter 索引包（带进度条；
失败才显示手动 URL 输入）。姿态模型在构建时自动打进 APK（约 9 MB），
没打进去也会在首次运行时下载一次。检索始终本地离线。

**滚动图库：** 首次启动一次性选择五组预设（更新频率 / 每次数量 / 自动删除 /
图库上限 / 网络条件，默认值已预选，点一次确认即可），后台任务按设定从
Wikimedia Commons 拉新图、在手机上提取姿态后合并进索引。长按缩略图 ★
收藏的图片永久保留，不参与自动清理。
