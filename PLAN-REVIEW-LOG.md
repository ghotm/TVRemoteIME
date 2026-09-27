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
