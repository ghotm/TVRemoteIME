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

---

# Plan: 任务五 — 影视仓 UI / 源管理补丁
_Locked via grill — by Claude + user（2026-09-27，用户实测反馈）_

## Goal

修复用户实测的 3 个问题：①详情在列表下方需拖动才能看到选集 ②「返回列表」交互无感（只隐藏内容）③源管理埋在页面底部不可见、且后端缺手动维护能力；补齐 grill 已确认但未实现的手动添加/删除/恢复内置源。

## Approach

### 1. 布局：左右分栏（用户选定）
- `index.html` 影视仓 Tab（`data-tab="movie"`）内重构：
  - 顶部「搜索 / 源管理」子导航 `.movie-subnav`（两个 `.movie-subnav-btn`）
  - 搜索子视图 `#movieSearchView`：工具栏 + `.movie-split` 分栏容器
    - 左栏 `#movieResults > #movieResultList`（结果列表，正常流）
    - 右栏 `#movieDetail`（详情，常驻显示，空态提示"选择左侧影片查看详情"）
  - 源管理子视图 `#movieSourceView`（默认 hidden）
- 点卡片 → 右栏填充详情（选集立即可见），不再上下堆叠
- **删除「返回列表」按钮**（分栏下无意义，仅保留「用系统播放器播放」按钮）
- `style.css`：`.movie-split` flex；右栏 `position:sticky; top` + `max-height:calc(100vh-…)` + `overflow-y:auto`(选集常驻可见)；**media query ≤1000px 转上下堆叠**（左列表在上、详情在下）且点击卡片时 `scrollIntoView` 滚动到详情

### 2. 源管理：独立子视图（用户选定）
- 子导航切到「源管理」→ `#movieSearchView` hidden、`#movieSourceView` 显示
- `#movieSourceView` 内容：
  - `#movieUpdateBtn`（检查更新源，原在顶部工具栏，移入此处）+ `#movieReseedBtn`（恢复内置源）+ `#movieSourcesStatus`
  - 添加表单：#movieAddName / #movieAddApi / #movieAddBtn
  - `#movieSourceList`：每项显示 启停 checkbox、名称、status、lastProbeError（title 悬浮）、api、**删除按钮**（内置源也允许删；删除后写入墓碑不会被更新复活，如需找回内置源用「恢复内置」；前端删除前 `confirm` 提示）

### 3. 后端 MovieRequestProcesser 补齐（经 oracle Round 1 REVISE 修订）
- **key 与去重统一**：新增 `normalizeApi(api)`（trim + 去尾部 `/`）与 `apiKey(api) = "s"+Integer.toHexString(normalizeApi(api).hashCode())`；**去重一律按归一化 api 匹配**（`findByApi`），不再按 name 或裸 hashCode key；`uniqueKey()` 处理 hashCode 碰撞（同 key 且 api 不同则追加 `_1/_2…`）
- **删除持久化（墓碑集，Round 3 修订）**：`movie_sources.json` 增加顶层字段 `removedApis`（归一化 api 字符串数组）。`removeSource` 删除源时把其归一化 api 写入 `removedApis`；`addSource` 成功添加时从 `removedApis` 移除该 api（用户主动重加即撤销墓碑）；`reseed` 恢复内置源时从 `removedApis` 移除内置源的 api。`updateSourcesSync` 的新增候选判定必须同时满足「`findByApi` 在当前文件不存在」**且**「api 不在 `removedApis`」，否则永久跳过——确保用户删掉的扫描源不会被每日更新复活
- `POST /movie/addSource {name, api}`：name/api 非空校验 → `findByApi` 已存在则返回提示 → **增强探测 `probeAdd(api)`**（请求 `{api}?ac=detail&wd=test`，要求响应含 `list` 字段才算通过，短超时）→ 通过才新建保存（key=apiKey）→ 失败把具体原因（含 SSRF 拒绝/超时/无响应）作为 msg 返回
- `POST /movie/removeSource {key}`：从 sources 数组移除、把该源归一化 api 加入 `removedApis`、原子保存（删除持久，不会被后续更新复活）
- `POST /movie/reseed`：对 BUILTIN_SOURCES 逐条按归一化 api 匹配，命中（含手动/扫描同源）则复用不补；未命中才以固定 key（hhzy/ikun）补回，enable 默认 true；**恢复的同时从 `removedApis` 移除对应 api**（撤销墓碑）
- **竞态修复（Round 2 + Round 3 修订）**：`updateSourcesSync` 三段式——①锁外扫描收集新增候选，**去重判定统一按 `findByApi`（归一化 api）**：命中即视为已存在、不新增（内置源 hhzy 与扫描到的 hhzyapi 互认，杜绝重复）；**且 api 命中 `removedApis` 墓碑时永久跳过**；②锁外对「现有源 + 新增候选」**全部执行 probe**（并发小池），刷新 status/lastProbeError（现有源状态不再冻结）；③`saveSources` 前置 `synchronized(this)` 锁内**重新 `loadSources()` 读取最新文件**（权威基线，含用户扫描期间的增删）：以最新文件为基底，**仅对 ② 中有 probe 结果的 key** 覆盖 status/lastProbeError（**不动 enable、不复活已删项**；扫描期间用户新加但未参与 ② 的源保持原值），再按 findByApi 并入真正新增项（须同时满足非墓碑；key 在锁内基于最新文件的 key 集合用 `uniqueKey` 计算，碰撞后缀 `_1/_2` 同锁内保证唯一）→ 原子保存。addSource/removeSource/reseed 同样在 `synchronized(this)` 内执行
- 新增端点统一响应约定：复用既有 `message("ok", msg)` → `{code:"ok", msg}` 与 `error(msg)` → `{code:"error", msg}`；前端按端点分别判断（sources/search/detail 看 `code==0` 与 `list` 字段；addSource/removeSource/reseed 看 `code` 是否为 `"ok"`，失败取 `msg` alert）——双套 code 属既有约定，此处明确以免混淆
- 路由 switch 追加 addSource / removeSource / reseed 三个 case

### 4. 前端交互（ime_core.js / style.css）（经 oracle Round 1 修订）
- `movieSearch()`：搜索时清空右栏显示空态
- `movieDetail()`：渲染到右栏（去掉 hidden 切换与「返回列表」按钮逻辑）；**`scrollIntoView` 放在详情 AJAX success 回调末尾**（窄屏堆叠时滚动到详情）
- 子视图切换：`.movie-subnav-btn` 使用独立 class、**不带 `data-tab` 属性**（避免误触发既有 `button.tab[data-tab="movie"]` 委托）；点击互切 `#movieSearchView`/`#movieSourceView` 的 hidden；**进入 movie tab 即调 `movieLoadSources()` + `movieRefreshPlayState()`**（保留既有 :1070 逻辑），切到源管理时再刷一次 `movieLoadSources()`
- **窄屏回退**：`#movieBackTop`「↑ 回到列表」按钮，CSS 默认隐藏、`@media(max-width:1000px)` 显示，点击滚动回列表顶部
- `movieLoadSources()` 扩展：删除按钮 → `confirm` 后 POST /movie/removeSource → 重载+提示（"已删除，不会被自动更新恢复"）；添加按钮 → POST /movie/addSource → 成功重载+清空表单、**失败 alert 后端 msg**；reseed → POST /movie/reseed → 重载+提示
- style.css 新增子导航/分栏/表单/删除按钮/回到列表按钮样式 + 分栏↔堆叠 media query

### 5. 构建验证
- 提交 feature/movie-station → 用户 push 触发 CI → 构建通过后浏览器验证布局与源管理、装电视验证

## Key decisions & tradeoffs

- **左右分栏**（用户选定，替代我的主从切换推荐）：电视大屏友好、结果与详情同时可见、"返回列表"按钮冗余可删；窄屏靠 media query 转堆叠 + 自动滚动兜底
- **子视图切换的源管理**：源管理不再埋在页面底部，入口明确
- **添加+删除+恢复内置（墓碑集）**：删除写入 `removedApis` 墓碑使其持久（不被每日更新复活）；重新 addSource 同 api 或 reseed 内置源时清除对应墓碑；内置源可删、可一键恢复
- **key/去重统一按归一化 api 匹配**：内置固定短 key 与扫描/手动 `apiKey` 并存时按归一化 api 命中互认，避免同源重复；不再按 name 去重（会误伤同名不同 api）
- **addSource 以增强探测通过为准**：请求 `{api}?ac=detail&wd=test` 且响应含 `list` 才算可达（裸 GET 返回 HTML 的假地址会被拒）；复用 HttpFetcher 的 SSRF/超时校验；失败即时反馈具体原因不落盘
- **无返回列表按钮**：分栏模式详情常驻，点卡片即换详情，"返回"语义消失（窄屏堆叠场景改用「↑ 回到列表」小按钮补救）

## Risks / open questions

- 分栏在 ≤1000px 需验证手机端体验（堆叠 + 「↑ 回到列表」按钮）
- 自定义源可达但需 UA/Referer 的仍可能播不了（Phase 2 本地 m3u8 代理，已知）
- 删除持久性由墓碑集 `removedApis` 保证：扫描合并时跳过墓碑 api（锁内重读最新文件只并入非墓碑新增项），用户删掉的源不会被每日更新复活；恢复途径为重新添加同 api 或点「恢复内置」
- **LAN 内网自建苹果CMS 源会被 SSRF 防护拒绝**（HttpFetcher 阻断私网/环回地址），该限制会在 addSource 失败信息中明确提示

## Out of scope

- 源排序/分组/编辑（改名改 API，删除重建即可）
- 自定义 UA/Referer、本地 m3u8 代理（Phase 2）
- 收藏/历史/连播（既有 Out of scope）

# Plan: 任务八 — 直播源更新（利用 awesome-zhuiju-free 的 tvbox_config）

_Locked via grill（3 项决策）+ oracle Round 1-2 修订 — by Claude + 用户_

## Goal
在视频 Tab 的现有直播区内新增「从仓库更新直播源」入口：从 awesome-zhuiju-free 的 tvbox_config 配置中提取 `lives[]` 直播源清单，用户点选某个源后，下载该直播列表 → 自动识别格式（M3U / TVBox txt）→ 转换为本项目 tv.txt 格式 → 整体替换（替换前自动备份）→ 前端刷新列表。手动为主，不做每日自动。

## 关键事实（评审确认）
- 现有 `tv.txt` 真实格式（`ime_core.js:26-60 parseTVData` 解析）：**方括号行是分组 `[分组名]`**，组内每行 **`频道名=url`**（name 为频道名/源名，url 为地址）；渲染时 `.tv-item` 显示分组名，组内每个 `频道名` 作为可点播链接。转换器必须输出 `[分组名]` + `频道名=url`，**不能**输出 `[频道名|分组]`。
- `TVRequestProcesser` 以 **UTF-8** 读写 tv.txt（`FileOutputStream` 直写，非原子）。
- `HttpFetcher.fetch()` 只返回 `String`（内部 `parseCharset` 默认 UTF-8 解码）→ GBK 回退必须拿到原始字节。

## Approach
1. **HttpFetcher 扩展**：新增 `public static Fetched fetchBytes(String url[, headers, connectTimeout, readTimeout])`，复用现有重定向/SSRF/gzip 逻辑，返回 `{ byte[] data; String charset /* 声明或 null */ }`。**必须保证：(a) 每一跳重定向仍调用 `checkPublicUrl`；(b) 返回的 data 是 gzip 解压后的字节**（沿用现有 `GZIPInputStream` 包裹顺序，勿漏）。原 `fetch(String)` 保留（内部委托 fetchBytes 后按 charset 解码）。`Fetched` 为静态内部类。
2. **LiveListConverter**（新类）：
   - 输入 `byte[]` + 声明 charset。解码候选：声明 charset（若非法则忽略）→ UTF-8 → GBK；选择**解码后 U+FFFD 替换字符最少**的结果。
   - 识别：去 BOM；`trim` 后以 `#EXTM3U` 开头 → M3U，否则 TVBox txt。
   - M3U：逐行；`#EXTINF:` 行取 `group-title`（缺省分组「直播」），频道名取**最后一个逗号之后**的内容；下一非 `#` 非空行 = url。相同 (分组, 频道名) **合并为多源**。无 `#EXTINF` 的裸 url 行 → 归默认分组，名称回退「源N」。
   - TVBox txt：`X,#genre#` 设当前分组；其余含 `,` 行 `频道名,url1#url2` → 频道 + 多源（`#` 分隔）；非 `#genre#` 的 `#`/`//` 开头行按注释忽略；无分组行归「直播」。
   - **输出 tv.txt（UTF-8）**：按分组输出 `[分组名]`，组内每个源的每个 url 一行 `频道名=url`；同频道多源时名称加后缀（`频道名`、`频道名(2)`…）避免重复；分组间空行。**不丢弃任何 url**（含内网 IPTV 地址）。
3. **LiveRequestProcesser**（server/，注册 RemoteServer GET/POST 两组）：
   - `GET /live/sources[?refresh=1]`：**后台单线程 executor + AtomicBoolean 互斥**；扫描 resources.json（github raw → CDN 备选）→ category=="tvbox_config" 逐配置 `fetchBytes`（短超时）解析 JSON 取顶层 `lives[]` → 聚合。**统一 schema** `{name, configName, url, error}`（失败项 `name`/`url` 为空串、`error` 有值，前端据此禁用「应用」）。结果写 `live_sources.json`（含 lastUpdated）。请求**返回当前缓存**；正在刷新则返回 `{refreshing:true, list:缓存}`；首次无缓存则触发刷新并返回 `{refreshing:true, list:[]}`。解析失败不阻塞整体，`error` 含 SSRF 拒绝/非 JSON 原因。
   - `POST /live/apply {url, name}`（`name` 仅用于日志/成功提示，不参与解析）：`fetchBytes` → LiveListConverter 转换 → **在 tv.txt 共享锁内**：把当前 tv.txt 备份为 `tv.txt.bak.<yyyyMMddHHmmss>`（保留最近 3 份，自动清理更旧）→ 原子写 tv.txt（临时文件 + rename）→ 返回 `{code:"ok", msg, channelCount, sourceCount, backup}`。任何失败**不触碰** tv.txt。
   - `GET /live/backups`：锁内返回备份列表 `[{file, time, size}]`（按时间倒序，最多 3 项）。
   - `POST /live/restore {file}`：锁内；`file` 必须是 basename 且形如 `tv.txt.bak.<数字>`（防路径穿越），且存在于备份列表 → 把该备份原子还原为 tv.txt → 返回 `{code:"ok",msg,time}`；非法/不存在 → `{code:"error",msg:"备份不存在"}`。**还原可逆**：用户可选择任意一份历史备份。
   - **共享锁**：在 `RemoteServerFileManager` 增 `public static final Object tvFileLock = new Object();`；`TVRequestProcesser` 的 GET/POST 读写 tv.txt 与 LiveRequestProcesser 的所有 tv.txt 读写**全部 synchronized(tvFileLock)**，消除并发写坏竞态。
4. **前端（video tab 直播区）**：
   - index.html：`.tv-header` 加 `<button id="btnLiveUpdate">📡 从仓库更新</button>`；新增 `#liveSourcePanel.hidden`（`#liveSourceStatus` + `#btnLiveRefresh` + `#btnLiveRestore` + `#btnLivePanelClose` + `.live-source-list`）。
   - ime_core.js：`liveLoadSources(force)` → GET `/live/sources`（force 时 `refresh=1`）→ 渲染 `.live-source-item`（源名 + 来源配置名 + 「应用」按钮；有 `error` 的标灰禁点）+ 若 `refreshing` 显示「正在获取配置…」并 3s 后重试；`liveApply(url,name)` → confirm → POST `/live/apply` → 成功 alert（频道数/源数）+ 若编辑器可见先隐藏（防覆盖未保存内容）+ `loadTVList()`；失败 alert msg。`liveRestore()` → GET `/live/backups` → 若空则 alert「暂无备份」；否则用简单列表/confirm 展示各时间戳供选择 → POST `/live/restore {file}` → `loadTVList()`。绑定 `#btnLiveUpdate` 展开面板并首次加载、`#btnLiveRefresh` 强制刷新、`#btnLiveRestore`、`#btnLivePanelClose`。
   - style.css：`.live-source-*` 面板/列表项/按钮样式。
5. **验证**：`node --check ime_core.js`；提交 feature/movie-station；用户 push CI → BlueStacks 验证。

## Key decisions & tradeoffs
- 入口在现有直播区（用户选定）；整体替换 + **时间戳备份（最近 3 份，可选择还原）**（用户选定「自动备份」）；手动为主（用户选定）。
- 转换输出严格对齐现有 `parseTVData` 语义（`[分组]` + `频道名=url`）。
- 直播列表下载走 SSRF 防护；生成的 tv.txt 保留原始直播 url（含内网 IPTV）。
- 编码：声明 charset 优先，UTF-8/GBK 择优（按 U+FFFD 最少）。
- tv.txt 所有读写共用 `tvFileLock`。
- `name` 参数仅展示用途；`/live/restore` 通过备份文件名白名单校验防路径穿越。

## Risks / open questions
- 多数 tvbox_config 配置无法直接解析（非 JSON/加密/多仓），可用 lives 可能有限；容错并在 UI 如实反馈「解析到 N 个源」与失败原因。
- 部分直播列表 url 为接口（需 token/过期）或不稳定，可能失效；MVP 不做有效性预检。
- 首次刷新需下载多个配置，耗时较长 → 后台任务 + 缓存 + 前端轮询。
- 大文件（610KB）转换内存：一次性读取可接受。

## Out of scope
- 直播源每日自动更新（用户选手动）。
- 多源合并追加（用户选替换）。
- 直播源有效性检测/排序。
- 加密配置（AES/Base64）与多仓 urls[] 展开（跳过容错）。
