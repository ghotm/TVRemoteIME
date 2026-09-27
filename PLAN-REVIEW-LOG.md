# Plan Review Log: TVRemoteIME Web 端影视仓功能

Act 1 (grill) complete — plan locked with the user. MAX_ROUNDS=5.
Reviewer: oracle (gateway/oracle) — codex CLI unavailable in this environment; oracle substituted per user decision.

---

## Round 1 — oracle (2026-09-27)

> 评审输出（摘要）：VERDICT: REVISE。16 项缺陷，其中 4 项高危阻断：
> 1. 【高】targetSdk 36 明文流量策略阻断所有 http:// 源（影视 API、tvbox 配置均为 http）；现有 HTTPGet 强制 HttpsURLConnection 会 ClassCastException
> 2. 【高】`vod_play_url` 线路分隔符应为 `$$$`（计划误写 `$$`）；详情应显式用 `ac=detail&ids=`（`wd` 是搜索）
> 3. 【高】awesome-zhuiju-free 实际在 `resources/resources.json`；`verification.status` 枚举为 pending/verified/recommended/caution/temporarily_unavailable/removed（计划漏 3 种）；status 是「配置级」非「站点级」，需自建探测；tvbox 配置含 http/中文 IDN 域名
> 4. 【高】`XLVideoPlayActivity.resetVideoPath` 从不回写 `mVideoPath` → 选集 ep2→ep1 回退失效（既有 bug）
> 5. 【中】ijk 分支未置位 isPlaying/lastPlayUrl，且无播放状态暴露端点
> 6. 【中】并发搜索/更新的线程池、超时、原子写未定义
> 7. 【中】无鉴权 LAN 服务新增「任意 URL 抓取」SSRF 攻击面
> 8. 【中】http 直链被误判直播（isLive=true）→ onResume seekTo(0) 丢进度、3s BUFFERING 提前 resume
> 9. 【中】HTTPS 证书/重定向/gzip 未覆盖；全局 trust-all 有 MITM 风险
> 10. 【中】ijkplayer-exo 可删且本地 jniLibs 与 AAR 同名 .so 重复
> 11. 【低】JSON 响应缺 charset、GET 中文参数解码
> 12. 【低】播放标题丢失（/play 无 title 参数）
> 13. 【低】「每天自动」调度机制未定义（IME 不常驻）
> 14. 【低】.gitignore 的 raw/ 规则已注释，`git add -f` 说明过期
> 15. 【低】系统播放器回退可能命中自身 ijk Activity（非 TCL 设备）
> 16. 【低】搜索无分页

### Claude's response（最终裁决）
- **采纳 1**：Manifest 加 `usesCleartextTraffic`（targetSdk 36 阻断 http 是硬伤）；新增独立 HttpFetcher（http+https、双超时、跟随跨协议重定向、gzip、不复用全局 trust-all）
- **采纳 2**：`$$$` 线路 / `#` 集 / `$` 名-址 分层解析并兼容 `$$`；详情显式 `ids=`
- **采纳 3**：数据路径 `resources/resources.json`、按 `category==tvbox_config` 过滤、完整枚举 6 种 status、自建逐站点可达性探测、IDN.toASCII 归一化
- **采纳 4**：修复 `resetVideoPath` 回写 `mVideoPath`（既有 bug，核心主链路）
- **采纳 5**：ijk 分支 markPlaying + 新增 `/movie/playState` 端点
- **采纳 6**：有界线程池 + Future.get(timeout) + 双超时 + 后台互斥更新 + 临时文件 rename 原子写 + 读写同步
- **采纳 7**：SSRF 基础校验（仅 http/https + 阻断私网/环回/链路本地）；不引入完整鉴权，与现有安全模型一致并在文档标注
- **采纳 8**：新增 intent 标志/按扩展名区分直播与 VOD，修 onResume seekTo(0) 丢进度
- **采纳 9**：新抓取工具自带重定向/gzip/证书处理；不改全局 trust-all
- **拒绝 10（部分采纳）**：经代码验证 `IjkVideoView.java:54` 显式 `import tv.danmaku.ijk.media.exo.IjkExoMediaPlayer`，**删除 exo 依赖必然编译失败**；改为保留依赖并将「同名 .so 重复」列为构建时验证项
- **采纳 11**：JSON 响应 charset=utf-8；确认 GET 中文参数 UTF-8
- **采纳 12**：`/play` 增加可选 `title` 参数透传
- **采纳 13**：服务启动时检查 lastUpdated>24h 后台拉取 + Handler 定时复查；IME 不常驻时降级为启动时检查
- **采纳 14**：更正计划中已过期的 `git add -f` 描述（raw/ 规则已注释）
- **采纳 15**：系统播放器回退时显式排除自身组件
- **拒绝 16**：保持 MVP 不分页（电视遥控场景默认 20 条已够，`pg` 留待后续），理由已记录

PLAN.md 已按以上裁定修订。

---

## Round 2 — oracle（源码阅读复评，2026-09-27）

背景：用户要求「我和 oracle 先阅读影视仓 App 源码再定方案」。影视仓本体未开源（o0HalfLife0o/TVBoxOSC 仅 README 分发页），oracle 精读同源主流实现 q215613905/TVBoxOS（克隆于 /tmp/opencode/yltv，TVBox 应用 + catvod 爬虫引擎）后复评。

> 评审输出（摘要）：VERDICT: REVISE。全部缺陷均有源码行号佐证：
> 1. 【必改】PLAN「按扩展名 .m3u8=直播」被参考实现明确推翻——直播是显式 PLAYER_IS_LIVE 标志（LivePlayActivity.java:429/1351、App.java:68），`.m3u8` 仅用于流类型/代理判断（IjkMediaPlayer.java:162）；应改显式 forceVod 标志并贯穿 /play→VideoPlayHelper→intentTo 链路
> 2. 【必改】vod_play_url 解析缺：集内 `$` 限 2 段 split("\\$",2)（SourceViewModel.java:1613）；集数倒序识别（VodInfo.java:88-119，线路≤5 时 isReverse 反转）
> 3. 【建议】线路名兜底对齐参考：min 长度迭代 + 跳过空 flag/url（AbsJson.java:140-142）
> 4. 【建议】本地 m3u8 代理（/proxy?go=live&type=m3u8 重写 + 附加 referer/cookie，Proxy.java:47-238）从永久 out of scope 改 Phase 2——它是解决 Referer 源的正解
> 5. 【建议】/play 预留 headers/referer 透传（IjkMediaPlayer.setDataSource(path, headers)）
> 6. 【建议】updateSources 容错加密/多仓配置（FindResult 支持 AES/Base64/;pk;、多仓 urls[]）
> 7. 【确认无需改】ac=detail&wd= 搜索 / ac=detail&ids= 详情、$$$ 分隔、不分页、usesCleartextTraffic（Manifest:34 同款做法）、原子写+rename 均被源码印证

### Claude's response（最终裁决）
- **采纳 1（必改）**：删除扩展名启发式；影视仓播放路径显式 `forceVod=true`（直播 out of scope）；`/play` 与 `title` 一起新增 `forceVod` 参数并贯穿 VideoPlayHelper→intentTo→XLVideoPlayActivity
- **采纳 2（必改）**：解析补 `split("\\$", 2)` 限 2 段 + 集数倒序自动识别（线路数≤5 时比较相邻集名数字反转）
- **采纳 3**：线路名按 min 迭代 + 跳过空；「线路1/2/3」兜底仅用于 vod_play_from 整体缺失的极端场景
- **采纳 4**：本地 m3u8 代理（Referer 源正解）列为 Phase 2，MVP 不做，Risks 注明
- **采纳 5**：/play 预留 headers/referer 透传结构，Phase 2 实现
- **采纳 6**：updateSources 对加密/多仓/无法解析配置跳过容错
- PLAN.md 已修订；进入 Round 3 复评。

---

## Round 3 — oracle（2026-09-27）

> 评审输出（摘要）：VERDICT: REVISE。Round 2 必改项/建议项文本层均已落实；仅剩 1 个实质问题 + 2 个低优先：
> N1【中·实质】forceVod 传递链的中间环节未逐一定义——PlayRequestProcesser 读参、VideoPlayHelper.playUrl 签名扩展、intentTo/newIntent putExtra、startDownloadTask 内 isLive 覆盖点均未写清；尤其**切集走 resetVideoPath→startDownloadTask 会重新把 isLive 置回 isLiveMedia()(true)**，若 forceVod 只在 onCreate 读一次，切第二集后 bug 复发
> N2【低】useSystem=true 时 forceVod 应被忽略，未点明
> N3【低】updateSources 探测失败原因未落盘暴露，建议 sources 状态附 lastProbeError

### Claude's response（最终裁决）
- **采纳 N1**：PLAN.md 第 1 节新增「forceVod 逐环节落点」——(a) PlayRequestProcesser 读 forceVod 参数（默认 false）；(b) VideoPlayHelper.playUrl 增 forceVod 入参；(c) intentTo/newIntent 增 forceVod 并 putExtra 存成员变量；(d) startDownloadTask 末尾（含 resetVideoPath 复用路径）统一覆盖 `isLive = !forceVod`
- **采纳 N2**：注明 useSystem=true 时 forceVod 被忽略
- **采纳 N3**：/movie/sources 状态附带 lastProbeError
- PLAN.md 已修订；进入 Round 4 复评。

---

## Round 4 — oracle（2026-09-27）→ ✅ APPROVED

> 评审输出（摘要）：VERDICT: **APPROVED**。Round 3 的 N1（forceVod 四落点 + useSystem 忽略）与 N3（lastProbeError）均已落实；全文复查无阻断级实质问题。仅存一处非阻断边缘情况：intentTo 复用路径（runningInstance）下 forceVod 成员变量不更新，仅影响 VOD↔直播跨 Tab 切换且需不先 stop；建议 resetVideoPath 同步更新即可，无需阻断。

### Claude's response（最终裁决）
- **采纳边缘加固**：PLAN.md forceVod 落点新增 (e)——intentTo 复用分支同步更新 forceVod 成员变量
- **评审结束**：4 轮，VERDICT: APPROVED。计划锁定，等待用户签收后进入实现。

## 评审总结（4 轮）
| Round | 结果 | 主要产出 |
|-------|------|---------|
| 1 | REVISE | 明文流量阻断、$$$ vs $$、awesome-zhuiju-free 结构与 status 枚举、resetVideoPath mVideoPath bug、状态端点、线程超时原子写、SSRF、直播/VOD 误判、重定向/gzip、exo 保留(拒绝10)、charset、title、调度、.gitignore、回退排除自身、分页(拒绝16) |
| 2 | REVISE | 源码复评：删 .m3u8=直播启发式→显式 forceVod、解析补 split($,2)+集数倒序、线路兜底对齐、本地 m3u8 代理改 Phase 2、/play 预留 headers、加密/多仓配置容错；确认 ids=/$$$/明文/原子写被源码印证 |
| 3 | REVISE | forceVod 四环节落点（切集复用路径 isLive 复发）、useSystem 忽略 forceVod、lastProbeError |
| 4 | APPROVED | 全部落实 + intentTo 复用同步 forceVod 边缘加固 |

---

# 任务五评审日志：影视仓 UI / 源管理补丁
Act 1 (grill) complete — 用户选定：左右分栏 / 源管理子视图切换 / 添加+删除+恢复内置。MAX_ROUNDS=5。

## Round 1 — oracle（2026-09-27）→ VERDICT: REVISE

> 评审输出（摘要）：2 项高 + 5 项建议：
> 1.【高】add/remove/reseed/toggle 与 `updateSourcesSync` 的 read-modify-write 竞态——updateSourcesSync 无锁，长时间扫描后整体覆盖，期间用户增删会「删了复活/加了丢失」
> 2.【高】key 生成与去重不可靠——内置短 key（hhzy/ikun）vs 扫描/手动 `s+hashCode`，同 api 两种 key 并存重复；hashCode 碰撞；按 name 去重误伤
> 3.【中】reseed 匹配标准未定义（按 key 还是 api），可能重复
> 4.【中】addSource 的 probe 仅裸 GET api 根，「非空即通过」门槛弱，会放行错误页
> 5.【低】响应 code 约定不一致（0/"ok"/"error"）、前端缺失败反馈
> 6.【低】scrollIntoView 时机（AJAX 渲染前调用会滚不到位）+ 删「返回列表」后窄屏回退
> 7.【低】addSource 受 SSRF 阻断，LAN 自建源无法添加且无明确提示

### Claude's response（最终裁决）
- **采纳 1**：`updateSourcesSync` 最后保存阶段移入 `synchronized(this)`，锁内重新 `loadSources()` 读最新文件，只并入「扫描新增项」，保留用户扫描期间的增删/启停；probe 结果只更新新增项
- **采纳 2**：新增 `normalizeApi`（trim + 去尾 `/`）与 `apiKey`，**去重一律按归一化 api**（`findByApi`），`uniqueKey()` 处理 hashCode 碰撞；内置源按 api 与新源互认
- **采纳 3**：reseed 按归一化 api 匹配，命中复用、未命中才以固定 key 补回
- **采纳 4**：addSource 改用增强探测 `probeAdd`（请求 `{api}?ac=detail&wd=test`，要求响应含 `list`）
- **采纳 5**：新端点统一 `{code:"ok"/"error", msg}`；前端 addSource 失败 alert msg、remove/reseed 提示
- **采纳 6**：scrollIntoView 移到详情 AJAX success 回调末尾；新增 `#movieBackTop`「↑ 回到列表」按钮仅窄屏显示
- **采纳 7**：addSource 失败时把具体原因（SSRF 拒绝/超时/无响应）作为 msg 返回前端；PLAN Risks 注明 LAN 源限制

PLAN.md 任务五章节已按以上裁定修订；进入 Round 2 复评。

## Round 2 — oracle（2026-09-27）→ VERDICT: REVISE

> 评审输出（摘要）：7 项中 5 项完整落实；1 高 + 4 中低：
> A.【高】合并判定自相矛盾——PLAN 去重说统一 findByApi，但 updateSourcesSync 合并仍写「当前文件缺失的 key」→ 内置源(hhzy key)会被扫描到的同 api(apiKey=s+hash)当新增插入，每天更新产生重复条目
> B.【中】probe 状态只更新新增项 → 现有源 status/lastProbeError 永久冻结，UI 色标失真
> C.【低】uniqueKey 碰撞后缀非 api 纯函数、且需在锁内基于最新文件计算
> D.【低】前端 subnav 按钮需避免与既有 `button.tab[data-tab="movie"]` 委托 handler 冲突；进入 movie tab 仍应触发 movieLoadSources
> E.【低】新端点 code("ok"/"error" 字符串)与既有端点 code:0(数字)类型不一致

### Claude's response（最终裁决）
- **采纳 A**：合并判定统一 `findByApi`——锁外扫描 collect 新增候选时即按归一化 api 去重（命中只补 status 不新增），内置源与扫描源互认
- **采纳 B**：probe 改为对「现有源 + 新增候选」全部执行（并发小池），刷新全部源的状态，不再冻结
- **采纳 C**：uniqueKey 在 `synchronized(this)` 内基于最新 `loadSources()` 的 key 集合计算；addSource/removeSource/reseed 均在锁内执行（同 doToggleSource 模式）
- **采纳 D**：subnav 按钮用独立 class/id、**不带 data-tab 属性**（避免误触发 movie tab 委托）；保留「进入 movie tab 即 movieLoadSources()」既有逻辑，切源管理子视图时再刷一次
- **采纳 E**：新端点统一 `{code:"ok"/"error", msg}`；前端各 handler 按端点检查对应字段，避免混淆

PLAN.md 任务五章节已修订；进入 Round 3 复评。

## Round 3 — oracle（2026-09-27）→ VERDICT: REVISE

> 评审输出（摘要）：上轮主因 A（合并判定）/B（状态冻结）已真正解决；新暴露实质问题 N1：删除非内置源会被下一次每日更新「复活」——③「以最新文件为基底 + 按 findByApi 并入真正新增项」只保证扫描期间删除不被覆盖；对上一轮更新之后删除的扫描源，其 api 仍在 tvbox 配置里，findByApi(最新文件) 返回 null → 被当新增项重新写入，每 24h 复活一次；且 Risks:160 措辞过度声称。N2：③逐项覆盖 status 对「扫描期间用户 addSource 但不在 probe 集合」的源语义不清。N3/N4：D（subnav/data-tab/进入 tab 加载）与 E（code 约定）仍未闭合。

### Claude's response（最终裁决）
- **采纳 N1（核心）**：引入持久化「墓碑集 `removedApis`」（movie_sources.json 顶层数组）——removeSource 写墓碑、addSource 成功撤销、reseed 撤销内置源墓碑；updateSourcesSync 新增候选判定 = findByApi 不存在 且 不在 removedApis（永久跳过墓碑）；Risks 修正措辞
- **采纳 N2**：③ 明确「仅对 ② 有 probe 结果的 key 覆盖 status/lastProbeError，其余保持原值」
- **采纳 N3**：subnav 独立 class 不带 data-tab；进入 movie tab 即 movieLoadSources()+movieRefreshPlayState()（保留 :1070 逻辑）
- **采纳 N4**：前端按端点分别判断双套 code 约定并写明

PLAN.md 任务五章节已修订；进入 Round 4 复评。

## Round 4 — oracle（2026-09-27）→ ✅ VERDICT: APPROVED

> 评审输出（摘要）：N1-N4 全部落实且墓碑方案并发自洽；未发现新实质问题。仅 3 处低 severity 实现级注意点：n1 probeAdd 网络 I/O 若整体在 synchronized(this) 内会短暂阻塞其他操作（推荐接受当前全锁内安全实现）；n2 ensureSeeded 首启未初始化 removedApis:[]（需 optJSONArray 空安全）；n3 removeSource 对不存在 key 应优雅返回 {code:"error",msg:"源不存在"} 不写墓碑。

### Claude's response（最终裁决）
- 全锁内安全实现照旧（单用户场景数秒串行可接受）；**n2/n3 直接纳入实现**（ensureSeeded 写 removedApis:[]、removeSource key 空返回不写墓碑）
- **评审结束**：4 轮，VERDICT: APPROVED。任务五计划锁定，等待用户签收后进入实现。

## 评审总结（任务五，4 轮）
| Round | 结果 | 主要产出 |
|-------|------|---------|
| 1 | REVISE | updateSourcesSync 竞态、key/去重不可靠、reseed 匹配、probeAdd 增强探测、code 统一+前端反馈、scrollIntoView+窄屏回退、SSRF 提示 |
| 2 | REVISE | 合并判定统一 findByApi（内置源不被扫描重复）、全源 probe 不冻结、uniqueKey 锁内计算、subnav 避让 data-tab、双套 code 约定 |
| 3 | REVISE | 墓碑集 removedApis 删除持久化、status 覆盖语义、D/E 闭合 |
| 4 | APPROVED | N1-N4 全部落实；实现级注意点 n2/n3 纳入实现 |

# Plan Review Log: 任务十 — 直播自定义源 + 多源合并

Act 1 (grill) complete — 用户 4 项决策 + 结构示意（源→分组→频道 三级，扁平化为 `源名 | 原始分组`）。MAX_ROUNDS=5。

## Round 1 — oracle（VERDICT: REVISE）
5 项缺陷：①【高】live_sources.json 无锁，refreshSourcesSync 与 addSource/removeSource 并发会丢自定义源 ②【中】同名源会意外聚合 ③【中】key 生成规则未定义，刷新后漂移 ④【中】clean() 只清洗分组名未清洗频道名（=、换行）⑤【低】新增公开 Channel 与旧私有 Channel 同名嵌套类编译冲突。建议 6-9：sourceCount==0 计入 failed；addSource 探测限大小/超时（2MB/10s）失败仍可存并标 error；前端轮询保留勾选；keys 无逗号分隔冲突。

### Claude's response
5 项全部采纳 + 4 建议全部采纳。PLAN 修订：新增 `liveSourcesLock`（与 tvFileLock 分离、不嵌套持有）；merge 显示前缀对重名加 (2)/(3)；key=`s`/`c`+hex(hash(normalizeUrl(url))) 稳定无逗号；clean() 同时清洗分组名与频道名；删除旧私有 Channel、改 Group/ChannelEntry；addSource 探测失败分档（超限/超时→保存标「未完整验证」；URL 非法/SSRF/无频道→不保存）；merge 单源异常或 0 频道计入 failed；前端重渲染恢复勾选。

## Round 2 — oracle（VERDICT: APPROVED）
9 项全部落实，锁设计无死锁。4 项低 severity 实现级注意点（不阻断，纳入实施清单）：
n1 addSource 需按 key/normalizeUrl 去重（已存在提示），hash 碰撞追加 _1/_2 后缀。
n2 addSource「保存但未验证」返回 `{code:"ok", saved:true, warning:"未完整验证"}`，前端成功后追加标黄提示。
n3 HttpFetcher 区分「大小超限/超时」（仍保存）与「SSRF/URL 非法」（不保存）建议用专用异常类型而非消息字符串匹配。
n4 merge 逐源串行下载可能阻塞请求线程，MVP 接受短超时兜底，后续可改后台任务/并行小池。
