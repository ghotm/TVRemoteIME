# Plan: TVRemoteIME Web 端影视仓功能

_Locked via grill — by Claude + user（2026-09-27）_
_Revised after adversarial review (oracle) Round 1 — 见 PLAN-REVIEW-LOG.md_

## Goal

在小盒精灵（TVRemoteIME，v2.0.0）Web 控制端（NanoHTTPD 9978 端口）新增「影视仓」能力：聚合直连型苹果CMS 影视站搜索资源 → 详情页按线路选集 → 推送到电视端用**项目自带 ijkplayer 播放器（XLVideoPlayActivity）**播放；并通过 [awesome-zhuiju-free](https://github.com/laoma2053/awesome-zhuiju-free) 仓库**每天自动 + 手动按钮**更新源清单与状态。

## Approach

### 1. 播放器接入构建
- `settings.gradle` include `:ijkplayer`、`:thunder`（已改）
- `IMEService/build.gradle` 增加 `implementation project(':ijkplayer')`（已改）
- **保留** `ijkplayer-exo:0.8.4` 依赖：`IjkVideoView.java:54` 显式 `import tv.danmaku.ijk.media.exo.IjkExoMediaPlayer`，删除会编译失败
- `xllib/DownloadManager.init()` try/catch 包裹 `XLTaskHelper.init()`（已改）
- `XLVideoPlayActivity`：
  - 新增静态 `stopIfRunning()`（已改）
  - **修复既有 bug**：`resetVideoPath()` 切换成功后回写 `mVideoPath = videoPath`，否则 ep2→ep1 被判「同 URL」no-op，选集回退失效
  - **区分直播 / VOD 直链（显式标志，绝不用扩展名启发式）**：新增 intent 标志 `forceVod`，影视仓播放路径一律 `forceVod=true`（直播本轮 out of scope）。参考影视仓实现（直播用显式 `PLAYER_IS_LIVE` 标志，`.m3u8` 仅用于流类型/代理判断）：**不能按 `.m3u8` 扩展名判定直播**，否则 HLS 点播被误判 `isLive=true`，`onResume` 里 `seekTo(0)` 丢进度、BUFFERING 3s 过早 resume 复发。`forceVod` 必须贯穿 `/play → VideoPlayHelper.playUrl → intentTo → XLVideoPlayActivity` 整条链路
  - **forceVod 逐环节落点**：(a) `PlayRequestProcesser` 读 `POST /play` 的 `forceVod` 参数（默认 false，兼容旧客户端）；(b) `VideoPlayHelper.playUrl(Context, url, videoIndex, useSystem[, forceVod])` 增加入参；(c) `XLVideoPlayActivity.intentTo/newIntent` 增加 `forceVod` 并 `putExtra` 保存为**成员变量**；(d) `startDownloadTask` 末尾（含 `resetVideoPath` 复用路径——切集也会走到）统一覆盖 `isLive = !forceVod`——否则单集正常、切第二集后 `isLive` 被 `isLiveMedia()` 重新置 true，进度丢失 bug 复发；(e) `intentTo` 复用分支（runningInstance 路径，不重走 onCreate）同步更新 forceVod 成员变量，防 VOD↔直播跨 Tab 切换使用旧值；`useSystem=true` 走系统播放器时 `forceVod` 被忽略
- `VideoPlayHelper`：
  - `playUrl()` 分支：`useSystem=false` → `XLVideoPlayActivity.intentTo(..., url, title)`；`useSystem=true` → 原系统播放器逻辑；ijk 启动异常时回退系统播放器（回退 Intent 显式排除自身 `player.XLVideoPlayActivity`，避免非 TCL 设备命中自己）
  - ijk 分支同样 `markPlaying(url)`
  - `stopPlay()` 前置调用 `XLVideoPlayActivity.stopIfRunning()`
- **默认 UA**：播放前给 `IjkMediaPlayer` 设 `OPT_CATEGORY_FORMAT` `user-agent` 为常见播放器 UA，不开放自定义

### 2. 明文流量（阻断项，必须修）
- `IMEService/src/main/AndroidManifest.xml` 增加 `android:usesCleartextTraffic="true"`（或 `network_security_config`）：targetSdk 36 下 **http:// 影视 API 与 tvbox 配置会被直接阻断**
- **新增 HTTP 抓取工具**（如 `http/HttpFetcher.java`）：支持 http+https、`connectTimeout`/`readTimeout` 双超时、**显式跟随重定向**（含 http→https 跨协议）、gzip 解压；不复用现有 `HTTPGet`（它强制 `(HttpsURLConnection)`，遇 http 抛 ClassCastException），不调用 `HTTPSTrustManager.allowAllSSL()` 的全局 trust-all

### 3. 后端：`server/MovieRequestProcesser.java`（GET+POST 各注册）
- `GET /movie/sources` → 源列表 JSON：名称 / api / 来源（内置|扫描|手动）/ 启停 / 状态（含 `lastProbeError` 字段便于排障）
- `GET /movie/search?wd=` → 启用源**并发**请求 `{api}?ac=detail&wd={keyword}`：
  - **有界线程池**（如 min(源数,8)）+ `Future.get(timeout≈8s)` 取消 + 连接/读取双超时；单源失败不影响整体
  - 合并去重（按 `vod_name`+`vod_year`），结果标注来源
  - 响应 `application/json; charset=utf-8`；GET 中文参数按 UTF-8 解码
- `GET /movie/detail?source={key}&id={vod_id}` → 详情：显式用 `{api}?ac=detail&**ids**={vod_id}`（`wd` 是搜索、`ids` 才是取 `vod_play_url`）
- **`vod_play_url` 解析**（对齐影视仓 AbsJson.java:136-146 / SourceViewModel.java:1613）：标准为 `线路名$$$第01集$url#第02集$url`；`vod_play_from` 与 `vod_play_url` 均按 `split("\\$\\$\\$")` 分线路，**min 长度迭代配对、跳过空项**；每集按 `split("\\$", 2)` **限 2 段**（防 URL 内含 `$` 截断），集名缺失用序号命名；线路名取 `vod_play_from` 分段，仅当其整体缺失时兜底「线路1/2/3」；**集数倒序自动识别**（线路数≤5 时比较相邻集名数字，倒序则 reverse，参考 VodInfo.java:88-119）；**兼容历史 `$$` 写法**（仅当 `$$$` 只拆出 1 段且含 `$$` 时）；处理 `list` 为数组/单对象、字段缺失、空 `vod_play_url`
- `POST /movie/updateSources` → 拉取 awesome-zhuiju-free → 扫描 tvbox 配置 → 提取 `type=1` 可达站点 → 合并源清单：
  - 数据路径 `resources/resources.json`；仅 `category=="tvbox_config"` 条目含配置 `url`
  - **配置格式容错**：个别 tvbox 配置可能是加密（AES/Base64/`;pk;`）或多仓（`urls[]`）格式（参考影视仓 ApiConfig FindResult / switchApiCollectionIfNeeded），无法解析则跳过该配置，不中断整体
  - 处理 `http://` 与中文 IDN 域名（`IDN.toASCII` 归一化）
  - **对提取出的每个 type=1 站点自行做可达性探测**（仓库 `verification.status` 只描述"配置文件"级别，不提供逐站点可用性）
  - `verification.status` 完整枚举：`pending / verified / recommended / caution / temporarily_unavailable / removed`；`removed`/`temporarily_unavailable` 自动停用标灰，`caution` 保留标黄，`recommended/verified` 正常
  - 跑**后台任务**并于搜索/更新间互斥；避免阻塞 NanoHTTPD worker
- `POST /movie/toggleSource` → 启停单个源
- `GET /movie/playState` → 返回 `isPlaying / lastPlayUrl`（前端轮询）
- 播放复用/扩展 `POST /play`：新增可选 `title` 与 `forceVod` 参数透传给播放器（`forceVod` 供影视仓播放一律置 true；Phase 2 预留 `headers/referer` 透传结构）
- **源数据持久化**：`context.getFilesDir()/movie_sources.json`；**原子写**（临时文件 + rename）；读写加同步
- **SSRF 基础防护**：源/配置 URL 仅允许 http/https；解析后阻断私网/环回/链路本地地址
- 内置兜底源：已实测直连站（豪华资源 hhzyapi.com、爱坤 ikunzyapi.com 等）

### 4. 源更新调度（每天自动 + 手动）
- 服务器启动时检查 `movie_sources.json` 的 `lastUpdated`，超 24h 则后台拉取
- 运行期用 `Handler` 定时（如每 24h）再检查；IME 不常驻时自然降级为「每次服务启动时检查」

### 5. 前端：Web 影视仓 Tab
- `index.html`：`nav.tabs` 新增 `<button class="tab" data-tab="movie">🎥 影视仓`；`.tab-content[data-tab="movie"]`：搜索框+按钮、结果列表（海报+标题+来源+简介）、详情面板（线路分组选集、内置播放 / 系统播放器按钮）、源管理区（列表+启停开关+「检查更新」+ 状态色标）
- `ime_core.js`：搜索 / 详情 / 选集 / 播放 / 源管理交互；轮询 `/movie/playState` 显示当前播放
- `style.css`：海报卡片 / 分组列表 / 开关样式

### 6. 构建验证
- 本环境无 JDK/SDK：用户执行 `./gradlew assembleDebug` 或 push 触发 GitHub Actions
- 部署到 192.168.50.75（TCL 电视，armeabi-v7a）验证：搜索→选集→内置播放→停止→系统播放器兜底

## Key decisions & tradeoffs

- **自带 ijkplayer 而非系统播放器直推**：可播更多格式、遥控器体验完整；代价 APK 变大、需处理迅雷 SDK 初始化（已 try/catch；http 直链播放绕过迅雷）
- **仅直连型苹果CMS 源**：稳定、无需移植 spider；代价排除 type=3/jar 与纯网页源
- **源清单 = 自动扫描 tvbox 配置 + 内置兜底 + 手动添加**：扩源自动化与可控性折中
- **每天自动 + 手动刷新**：时效与资源折中；status 参考仓库「配置级」+ 自建逐站点探测
- **内置默认 UA、不开自定义**：接口最简；代价少数需 Referer 的源播不了
- **不做收藏/历史、不做分页**：聚焦主链路（电视遥控场景首页 20 条已够）
- **不改全局 HTTPS trust-all**：新抓取工具自带重定向与证书处理，避免扩大 MITM 面

## Risks / open questions

- 电视端（192.168.50.75）能否直连外网影视 API 未实测，部署验证
- `ijkplayer-java/exo:0.8.4` 在阿里云 public 仓库可用性已实测 200；本地 `jniLibs` 与 AAR 可能同名 `.so` 重复 → 首次构建验证并按需去重
- 迅雷 `.so` 在 Android 11 加载行为未实测（已 try/catch）
- 部分 m3u8 需 Referer 会播放失败（MVP 接受，靠「系统播放器」兜底；**Phase 2 实现本地 m3u8 代理**：`/proxy?go=live&type=m3u8|ts&url=` 重写 m3u8 内 ts/子 m3u8/key 为本地地址并附加 ua/referer/cookie，参考影视仓 Proxy.java:47-238——这是解决 Referer 源的正解）
- 仓库配置内 type=3/jar 站点自动跳过；`lives[]` 直播源本轮不接入
- 该 Web 服务无鉴权（现状）；新增电影端点仅做 SSRF 基础校验，不引入鉴权（与现有安全模型一致，风险已在文档标注）
- 新 `res/raw` 文件**无需** `git add -f`：`.gitignore` 的 `raw/` 规则已注释（此前说明已过期）

## Out of scope

- TVBox spider / jar / drpy 解析引擎
- 收藏、最近播放历史
- 自定义 UA/Referer；**盒子端流代理（本地 m3u8 代理）→ 列为 Phase 2**，MVP 不做（Risks 已注明价值与参考实现）
- 电视端「下一集」连播
- magnet / 种子 / 迅雷边下边播扩展
- 直播源（lives）接入
- 搜索结果分页（`pg` 参数，后续按需）
- 电影端点鉴权体系
