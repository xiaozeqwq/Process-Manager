# Changelog

Process Manager（原 MCC In Server）全量版本对账，范围 `v1.0.0` → `v1.0.11`。

数据来源：`git log` / `git diff` / tag 树 / GitHub Actions run 记录 / Release 资产清单。

聚合规模：26 个提交（17 个非合并提交），68 个文件，`+4974 / -2055`。

| 版本 | Tag 提交 | 日期 | 提交数 | 文件 | 行变更 |
|---|---|---|---|---|---|
| v1.0.0 | `1f73b04` | 2026-09-13 | 基线 | - | - |
| v1.0.1 | `a05f0a3` | 2026-10-11 | 1 | 52 | +1076 / -914 |
| v1.0.2 | `11f589b` | 2026-10-25 | 7 | 24 | +2056 / -83 |
| v1.0.3–v1.0.5 | 无 tag | 2026-10-25 | 3（作废） | 5 | +84 / -3 |
| v1.0.6 | `9db2238` | 2026-10-25 | 4 | 5 | +84 / -3 |
| v1.0.7 | `9ee3d04` | 2026-10-25 | 1 | 2 | +2 / -1 |
| v1.0.8 | `95d1f99` | 2026-10-25 | 1 | 5 | +27 / -1 |
| v1.0.9 | `0f855a2` | 2026-10-25 | 1 | 3 | +13 / -3 |
| v1.0.10 | `a4058e8` | 2026-10-28 | 1 | 10 | +767 / -102 |
| v1.0.11 | `25037e0` | 2026-10-29 | 1 | 2 | +4 / -3 |

---

## v1.0.0 — 基线

`mccinserver` / `MCC In Server`，包名 `com.xiaoze_qwq_.mis`，命令 `/mis`，配置 `mis_config.toml`，2 个发布资产（Fabric + NeoForge）。Architectury 多加载器，MC 1.21.1。

---

## v1.0.1 — 中性化重命名

**refactor**
- `mod_id: mccinserver → processmanager`，`mod_group_id: com.xiaoze_qwq_.mis → com.xiaoze_qwq_.processmanager`
- 包名 `mis/**` → `processmanager/**`，入口类 `MCCInServer{Fabric,NeoForge}` → `ProcessManager{Fabric,NeoForge}`
- 命令根 `/mis` → `/pm`
- 配置文件 `mis_config.toml → pm_config.toml`、`mispassword → pmpassword`、`mis_secret.key → pm_secret.key`
- AW 文件 `mccinserver.accesswidener → processmanager.accesswidener`，语言命名空间同步，`fabric.mod.json` / `neoforge.mods.toml` 同步
- CI 产物名改为从 `gradle.properties` 动态读取；`org.gradle.workers.max=4`

**breaking**
- 无配置迁移逻辑：旧 `mis_config.toml`、旧 `/mis` 命令、旧 `mccinserver` 资源域全部失效
- 模组 ID 变更，旧存档内的物品/网络通道不再对应

---

## v1.0.2 — SSH 控制台接入（架构第一版）

**feat**
- `62526c6` 内嵌 MINA sshd 2.13.2；`ProtocolSniffHandler` 在 MC 端口按首包 `SSH-` 前缀分流；`ListenerHook` 前置注入子通道；`PtyShellCommand` 基于 pty4j 0.12.13 提供真实 PTY 交互 shell；全局 `[ssh]` 配置；AW 暴露 `ServerConnectionListener.channels`
- `5c102a1` 改为按实例配置：`sshEnabled` / `sshUser` / `sshPasswordHash`（bcrypt `$2b$`）；命令 `/pm <inst> ssh status|enable|disable|setuser|setpassword`；启用 SSH 的实例改用 PTY 启动；SSH 会话改为控制台桥接（`InstanceConsoleCommand`），删除 shell 与 `PtyShellCommand`；启用 SSH 的实例禁止 immersive

**fix**
- `6d9798a` 状态指标：CPU 改按进程树 kernel+user 时间差采样；内存累加进程树 RSS；运行时长用真实启动时间；网络抽为 `HostNetworkMonitor` 主机级单例；OSHI `SystemInfo` 共享单例
- `c2d225d` 桥接本地端口 → 真实客户端 IP 映射（修复审计与限流失效）；退出前排空输出队列；桥接建立前缓存首包；退出后等输出线程 EOF；运行中禁止启停 SSH；启动失败清理监听器与 sshd；锁定过期重置失败计数；桥接复用事件循环 Channel 类型（Epoll/KQueue）
- `bfb59d5` 排除 pty4j 的 `purejavacomm` 传递依赖

**refactor**
- `Config` 从全局 `[ssh]` 迁移到每实例 `sshEnabled/sshUser/sshPasswordHash`，新增 `getSshEnabledInstance()` 单实例语义
- 进程输出通道从「管道 / PTY」二选一，改为 `ptyMode = sshEnabled`

**breaking**
- 配置 schema 变更（全局 `[ssh]` → 实例级字段）
- 交互式 shell 被移除，SSH 仅剩控制台桥接

---

## v1.0.3 / v1.0.4 / v1.0.5 — 未发布

三次打包尝试，版本号已提升但从未打 tag、从未出 Release，被 v1.0.6 覆盖。

- `643d25c` 重定位 `com.sun.jna` / `com.pty4j` 及注解包到 shadow 命名空间，并同步重定位 pty4j 原生资源路径
- `7f19fcc` 收缩为「仅重定位 jna-platform」，放弃重定位 `com.sun.jna`
- `19a0cf9` 引入 fat / thin / dep 三种变体

---

## v1.0.6 — 产物形态重构

**fix / refactor**
- 发布 fat、thin 两个 JAR，外加独立 `jna-5.12.1.jar`、`jna-platform-5.12.1.jar`，共 6 个资产
- thin 仅排除 `com/sun/jna/**` 与 `META-INF/versions/**/com/sun/jna/**`，保留 pty4j，不声明依赖、不自动加载 JNA
- fat 面向纯净服；thin 面向整合包已有 JNA 的场景

**breaking**
- 发布模型从 2 资产变为 6 资产；thin 用户需手动放置 JNA

---

## v1.0.7 — NeoForge 访问控制

**fix**
- `neoforge/src/main/resources/META-INF/accesstransformer.cfg` 新增 `public net.minecraft.server.network.ServerConnectionListener channels`
- 原因：`processmanager.accesswidener` 在 NeoForge 生产环境不会被转换成 AT，`ListenerHook` 触发 `IllegalAccessError`

**breaking**
- 无用户侧变更

---

## v1.0.8 — pty4j 依赖回补

**fix**
- 重新引入 `org.jetbrains.pty4j:purejavacomm:0.0.11.1`（`implementation` + `shadow`）
- 根 `build.gradle` 增加 `https://packages.jetbrains.team/maven/p/ij/intellij-dependencies`
- 原因：`com.pty4j.unix.PtyHelpers` 静态初始化引用 `jtermios.JTermios`，缺失即 `NoClassDefFoundError`

**breaking**
- 该构件只存在于 JetBrains 仓库，离线/镜像构建需要额外配置

---

## v1.0.9 — 主机密钥算法

**fix**
- `SimpleGeneratorHostKeyProvider` 从 `Ed25519` 改为 `EC` + `setKeySize(256)`
- 密钥文件名 `pm_ssh_hostkey.ser → pm_ssh_hostkey_ec.ser`，避免复用旧算法的密钥
- 原因：MINA sshd 2.13.2 的 Ed25519 依赖可选 `net.i2p.crypto:eddsa`，未打包时 `getKexProposal() no resolved signatures available`

**breaking**
- 服务端主机密钥更换，客户端 `known_hosts` 报 host key 变更

---

## v1.0.10 — 多实例 + 双认证（架构第二版）

**feat**
- SSH 用户名解析为实例编号或实例名称（不区分大小写）；未匹配时回退到可配置默认实例，仅校验该实例密码
- 多个实例可同时启用 SSH
- 每实例独立 `sshAuthMode`：`password`（bcrypt）或 `publickey`
- 公钥存 `config/.ssh/<实例编号>.pub`，由 MINA `AuthorizedKeysAuthenticator` 校验，支持热重载
- 新命令：`ssh auth password|publickey`、`ssh keys`、`ssh addkey`、`ssh delkey`、`ssh default`、`ssh default set`、`ssh default clear`
- 默认实例回退仅用于密码认证；默认实例强制「已启用 + 密码模式 + 已设密码」
- 会话按实例跟踪（`SESSION_INSTANCE_ID` attribute），停用实例时只断开其会话
- 新增 `net.i2p.crypto:eddsa:0.3.0`（不重定位），用于解析 ed25519 公钥

**refactor**
- `Config` SSH 段整体重写：`enforceSingleSshInstance` → `normalizeSshInstances`，`getSshEnabledInstance` → `getSshEnabledInstances` + `hasEnabledSshInstance`，新增 `findSshInstance` / `findSshInstanceWithFallback` / `isSshAuthReady` / 公钥读写
- `SshServerHost` 改为双 authenticator + `Event.Authenticated` 登记会话 + `Event.KexCompleted` 限流；公钥认证在签名校验后才占会话名额
- 默认实例持久化到 `pm_ssh_default`（空串表示显式关闭回退）
- `InstanceManager.reloadInstances` 用集合比较启用实例集合，运行中检测 `sshEnabled` / `sshAuthMode` 变更并回退持久化
- `applySshConfigurationChange` 改为按需启停 + 断开停用实例的会话

**breaking**
- SSH 用户名语义反转：从「忽略用户名」变为「用户名选择实例」
- `sshUser` 字段废弃，仅告警忽略
- 单实例约束移除，`sshPasswordHash` 之外新增 `sshAuthMode`

---

## v1.0.11 — 默认实例一致性

**fix**
- 默认实例被删除时，把 `pm_ssh_default` 写回空串并记录告警
- `mod_version 1.0.10 → 1.0.11`

**breaking**
- 无

---

## v1.0.12 — 安全与生命周期修复，移除文件日志

**security**
- Cookie 自动登录令牌强制校验账号归属：令牌所属 UUID 与本机玩家不一致时拒绝并告警（`CookieAuthPacket`）
- 客户端令牌存储键改为 `玩家UUID@服务器地址`，同一客户端不同账号不再共用授权（`ClientCookieStorage.currentAccountKey`）
- 修改或清除 PM 主密码后，作废全部授权与已签发令牌（`SessionManager.revokeAllAuthorizations`）
- `/pm auth revoke` 现在同时失效该玩家的全部 Cookie 令牌
- PM 主密码增加规则校验：长度 6–128、禁止首尾空白、禁止内部空白；`/pm verify` 改用 `greedyString`
- SSH 公钥认证失败现在计入 `SshGuard` 锁定，达到阈值立即断开连接；新增/删除公钥后失效认证器缓存
- NeoForge `accesstransformer.cfg` 补齐 `CommandSourceStack source`
- 配置文件与 `pm_auth.toml` 改为临时文件 + 原子替换，避免写入中断损坏配置

**fix**
- 手动 stop / forcestop 不再触发 autoRestart：新增 `manualStopRequested` 标志，并记录 `pendingRestart` 以便取消
- `ProcessInstance.shutdown()` 关闭调度器、输出读线程与输出刷新任务；实例移除与关服时调用
- 关服后清空 `serverInstance`，防止调度任务在服务端停止后重新拉起进程
- `RingBuffer.resize` 缩容时保留最新条目，修复 `ArrayIndexOutOfBounds`；容量下限为 1
- 状态界面关闭时发送新的 `StatusUnsubscribePacket` 退订，服务端另设 15s 无请求自动剔除
- 控制台输出改为 50ms / 64 行合并下发，客户端按行拆分渲染，降低包数量与 GC 压力
- 沉浸模式补历史改用累计输出行数计算，避免重复打印
- `ProcessInstance.start()` 返回真实启动结果；`forceStop` 立即 `destroy()`，`stop` 先发停止命令再等待

**removed**
- 移除文件日志：删除 `OutputLogger`、`logDirectory` / `compressionFormat` / `maxArchives` / `saveLogTime` / `timeFormat` 配置项及对应命令文案，移除 `commons-compress` 依赖
- 旧配置中的上述字段会记录告警后忽略；进程输出仍保留在内存历史供 GUI 与控制台查看

**build**
- 新增 `common/src/test`（JUnit Jupiter，BOM 6.1.3）与 `:common:test`，CI 在编译任务之后、发布任务之前执行单元测试
- `mod_version 1.0.11 → 1.0.12`

**breaking**
- 不再生成 `output/*.log` 及归档文件

---

# 架构演进审计

## 一、成本溯源（推断值）

仓库内没有 token 级埋点，以下按「产物 + CI + 工具输出体积」推断，标注为估算。

| 区间 | 占 Token | 依据 |
|---|---|---|
| v1.0.1 重命名 | ~3% | 52 文件全量重命名 diff |
| v1.0.2 SSH 初版 + 评审修复 | ~18% | `+2056/-83`，24 文件，含 374 行嗅探器与 253 行控制台桥接 |
| v1.0.3–v1.0.8 打包/类路径调试 | ~39% | 2026-10-25 03:13–04:39 内连推 6 次；`/tmp` 留存 `Native.java` 105KB、`dispatch.c` 122KB、`jnatree.json` 470KB、`ShadowJar.kt` 30KB，以及 6 套解压产物 |
| v1.0.9 MINA 源码深挖 | ~12% | 提取 1152 个 Java 文件 / 163,718 行 sshd 源码用于确认 `KexCompleted`、`AuthorizedKeysAuthenticator`、`resolvePublicKey` |
| v1.0.10 多实例重写 | ~23% | `+767/-102`，`Config` 825 行、`CommandRegistry` 938 行、`SshServerHost` 443 行交叉引用核对 |
| v1.0.11 + 收尾 | ~5% | 单文件 5 行修复 + 产物复核 |

消耗最高的是 v1.0.3–v1.0.8：约 100 分钟内 6 次发布，每次都要「下载 6 个 JAR → 解压 → 统计包根 → 回读 build.gradle 重排 shadow 规则」。

Context 爆炸的四个来源，按贡献排序：

1. **构建产物验证循环**：37 次 GitHub Actions run，每次轮询返回完整 jobs/steps JSON；每个版本再叠加 4–6 个 JAR 的类清点。
2. **全量第三方源码注入**：MINA 16 万行、JNA `Native.java` / `dispatch.c`、pty4j 源码，属于一次性大块写入且几乎不淘汰。
3. **无本地 JDK 的 CI 重试环**：只能 push → 建 PR → 睡眠轮询 → 读失败日志 → 改 Gradle → 再 push。每轮都携带前置全部结论。
4. **同一结论的重复推导**：`purejavacomm` 排除后又加回、JNA 重定位三次反复、默认实例语义反复，每次都要重新读取上下文确认。

## 二、技术债：SSH / 密钥 / 类加载

**SSH 接入模型：2 次重写**

- 第一版：全局 `[ssh]` + `PtyShellCommand` 真实 shell（v1.0.2 `62526c6`）
- 第二版：每实例 `sshEnabled` + 控制台桥接，删除 shell（v1.0.2 `5c102a1`）
- 第三版：多实例 + 用户名选实例 + 密码/公钥双认证（v1.0.10）

用户名语义完整绕了一圈：`sshUser` 映射实例 → 忽略用户名单实例 → 用户名选实例。

**JNA / shadow 打包：3 次重写，2 个版本号打水漂**

全量重定位 → 仅重定位 jna-platform → 完全不重定位、改 fat/thin 拆分。中间 v1.0.3 / v1.0.4 / v1.0.5 三个版本号未发布即作废。

**pty 依赖：1 次明确反向**

v1.0.2 在提交信息里写下「unix PTY 实现实际使用 JNA，无需该依赖」并排除 `purejavacomm`；v1.0.8 因 `NoClassDefFoundError: jtermios/JTermios` 原样加回。

**主机密钥：1 次算法替换**

Ed25519 → EC P-256，同时换文件名，客户端 `known_hosts` 需要清理。

**合计：6 次已发布的架构反转 + 1 次 v1.0.10 会话内的默认实例语义自翻（先自动选最小合格实例，改为只认显式配置）。**

## 三、上下文压缩与遗忘

**淘汰机制**：实际发生的是「会话摘要 + 外部记忆」双轨。

- 会话侧依靠摘要式压缩，把历史折叠成「目标 / 约束 / 已完成 / 阻塞 / 关键决策」清单；本次对话开头那份含 `1.0.6 → 1.0.11` 版本流水、行尾约定、MINA 2.13.2 源码位置的状态块就是压缩产物。
- 项目侧依靠 `.monkeycode/MEMORY.md` 做持久化，把「构建变体规则」「NeoForge AT」「pty4j/purejavacomm」「EC 主机密钥」「eddsa」写成跨会话约束。

**确认发生的遗忘**：

1. `purejavacomm`：v1.0.2 排除 → v1.0.8 加回。中间 12 天，判断依据在提交信息里，结论是错的。
2. JNA 重定位：`643d25c` 已重定位并同步资源路径，`7f19fcc` 又部分回退，最终全部放弃。三次之间间隔 12–30 分钟，属于短循环内的立场反复。
3. 默认实例语义：v1.0.10 开发中先实现「未配置则自动选编号最小合格实例」，随后自行推翻为「只认显式配置」，同一会话内两次改判。
4. 版本号：v1.0.3/1.0.4/1.0.5 连推三轮未发布，等于三轮构建成本未产生任何交付。

**未再犯的部分**：写入 `MEMORY.md` 的结论（fat/thin 6 资产、AT 必须单独声明、`purejavacomm` 不可排除、主机密钥必须 EC、`net.i2p.crypto` 不可重定位）在后续版本中一次都没有被再次破坏。这份文件是本次长会话里唯一稳定的抗遗忘机制。

## 四、后续动作建议

1. 把「产物验证」固化成脚本（解压 + 包根断言 JNA/eddsa/jtermios/AT），替代每版本人工清点，这是单项最大的 token 开销。
2. 把第三方库结论只写进 `MEMORY.md` 的一条结论，源码不在上下文中保留。
3. 发布前先跑一遍版本号占位检查，避免 v1.0.3–v1.0.5 这类作废版本号。
4. SSH 相关命令与配置字段已在 v1.0.10 收敛，后续变更走增量，不再整体重写 `Config` SSH 段。
