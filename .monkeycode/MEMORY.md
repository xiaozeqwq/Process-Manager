# User Instruction Memory

This file records user instructions, preferences, and teachings for reference in future interactions.

## Format

### User Instruction Entry
User instruction entries should follow this format:

[User Instruction Summary]
- Date: [YYYY-MM-DD]
- Context: [Mentioned scenario or time]
- Instructions:
  - [Content of user teaching or instruction, described line by line]

### Project Knowledge Entry
Entries discovered by the Agent during task execution should follow this format:

[Project Knowledge Summary]
- Date: [YYYY-MM-DD]
- Context: Discovered by Agent while performing [specific task description]
- Category: [Operations & Deployment|Build Methods|Testing Methods|Troubleshooting & Debugging|Workflow & Collaboration|Environment Configuration]
- Instructions:
  - [Specific knowledge points, described line by line]

## Deduplication Strategy
- Before adding a new entry, check for similar or identical instructions.
- If a duplicate is found, skip the new entry or merge it with the existing one.
- When merging, update the context or date information.
- This helps avoid redundant entries and keeps the memory file tidy.

## Entries

[强制中文交流]
- Date: 2026-10-11（2026-10-24 用户再次明确）
- Context: 用户在处理 Architectury 模组重命名任务时明确提出，后续在代码审查与修复中再次强调
- Instructions:
  - 思考过程与对话回复一律使用中文
  - 后续所有任务均遵循此约定
  - 用户明确要求将此类行为指令持续记录到 `.monkeycode/MEMORY.md`

[编译使用 GitHub Actions]
- Date: 2026-10-11（2026-10-25 补充触发条件）
- Context: 用户指出本项目的编译验证一贯通过 GitHub Actions 完成
- Instructions:
  - 不要在本机安装 JDK 或执行本地 gradle 构建
  - 编译验证通过 push 分支并触发 GitHub Actions workflow 完成
  - `.github/workflows/build.yml` 仅在 push 到 `master`/`main` 或 `pull_request` 时触发；功能分支需要创建 PR 才会运行 CI
  - workflow 中的 jar 名等应从 gradle.properties 动态读取（如 mod_id、mod_version），避免硬编码

[部署环境只开放单个端口]
- Date: 2026-10-21
- Context: 用户说明因特殊原因，对外网仅开放一个端口，SSH 需与该端口复用
- Category: Operations & Deployment
- Instructions:
  - SSH 与 Minecraft 必须继续共用同一个公网端口，不能改为独立对外端口
  - 安全加固需在单端口约束下完成（内嵌多路复用或本机同端口代理）

[SSH 仅作为 PTY 控制台通道]
- Date: 2026-10-21
- Context: 用户澄清 SSH 功能定位，避免引入 Linux/Windows 用户体系
- Category: Environment Configuration
- Instructions:
  - SSH 只负责进程输出到客户端、客户端输入到进程，必须使用伪终端
  - SSH 用户名用于选择实例：实例编号或实例名称；未匹配任何实例时回退到可配置的默认实例，并校验该实例密码
  - 允许多个实例同时启用 SSH；每个实例独立选择 password 或 publickey 认证，公钥存放在 config 下的 `.ssh/<实例编号>.pub`
  - 默认实例必须使用密码认证，可通过 `/pm <实例> ssh default set` 配置
  - 不读取系统 authorized_keys，不提供 shell、命令执行、SFTP/SCP、端口转发

[每次更新必须提升版本]
- Date: 2026-10-25
- Context: 用户明确要求每次更新都要提升版本并重新构建
- Category: Build Methods
- Instructions:
  - 每次修改合入 `master` 前必须提升 `gradle.properties` 中的 `mod_version`
  - 版本提升后通过 GitHub Actions 重新构建，并发布对应版本 tag 的 Release
  - 禁止复用已存在的版本号发布新构建，避免 Release tag 指向旧提交

[模组构建必须提供 fat/thin 两种变体及 JNA 依赖包]
- Date: 2026-10-25
- Context: 用户报告模组内置 JNA 与整合包内其他模组发生 NeoForge 模块包冲突；1.0.4 仍报 `com.sun.jna.internal` 冲突
- Category: Build Methods
- Instructions:
  - 每个加载器（Fabric、NeoForge）构建 fat、thin 两个 JAR，并额外发布 `jna`、`jna-platform` 两个依赖包，共 6 个发布文件
  - fat：完整打包 JNA、jna-platform、pty4j，面向无 JNA 冲突的纯净服
  - thin：排除 `com/sun/jna/**` 与 `META-INF/versions/**/com/sun/jna/**`，保留 pty4j，不声明依赖，由用户手动放入 JNA 包
  - 不要重定位 `com.sun.jna`：JNA 原生库硬编码 `Java_com_sun_jna_Native_*` 符号和 `com/sun/jna/...` 类名，重定位会导致 `UnsatisfiedLinkError`
  - 不要重定位 `com.pty4j` 或 `resources/com/pty4j/native`：pty4j 原生资源路径同样硬编码
  - 其余 bundled 第三方包仍需重定位（sshd/commons/toml/gson/bcrypt/annotations 等）
  - 新增依赖后检查 fat 与 thin 产物的包根目录，确认 thin 中不存在任何 JNA 类或原生库

[NeoForge 生产环境不应用 Fabric Access Widener]
- Date: 2026-10-25
- Context: 1.0.6 thin 在 NeoForge 启用 SSH 时，ListenerHook 访问 ServerConnectionListener.channels 抛 IllegalAccessError
- Category: Troubleshooting & Debugging
- Instructions:
  - 跨加载器访问私有成员不能只依赖 `processmanager.accesswidener`；NeoForge 生产 jar 不会把 AW 转成 AT
  - NeoForge 侧必须在 `neoforge/src/main/resources/META-INF/accesstransformer.cfg` 单独声明，例如 `public net.minecraft.server.network.ServerConnectionListener channels`
  - 发布前用 `unzip -l` 确认 neo jar 内存在 `META-INF/accesstransformer.cfg`

[pty4j 必须附带 JetBrains purejavacomm]
- Date: 2026-10-25
- Context: 1.0.7 启动实例时 `NoClassDefFoundError: jtermios/JTermios`
- Category: Build Methods
- Instructions:
  - pty4j 的 `com.pty4j.unix.PtyHelpers` 依赖 `jtermios`，该类位于 `org.jetbrains.pty4j:purejavacomm`
  - 不要从 pty4j 依赖中排除 purejavacomm；需在 fabric/neoforge 的 implementation 与 shadow 中都显式声明 `org.jetbrains.pty4j:purejavacomm:0.0.11.1`
  - 该构件只在 JetBrains 仓库 `https://packages.jetbrains.team/maven/p/ij/intellij-dependencies`，根 build.gradle 必须添加该仓库
  - purejavacomm/jtermios 不要重定位，pty4j 直接按包名引用

[内嵌 SSH 主机密钥使用 EC，不要用 Ed25519]
- Date: 2026-10-25
- Context: 客户端连接报 `getKexProposal() no resolved signatures available`
- Category: Troubleshooting & Debugging
- Instructions:
  - Apache MINA sshd 2.13.2 的 Ed25519 支持依赖可选的 `net.i2p.crypto:eddsa`，未打包时服务端无法提供签名算法
  - 主机密钥改用 `EC`（P-256）或 RSA；不要设置 `Ed25519`
  - 更换主机密钥算法时必须更换密钥文件名（如 `pm_ssh_hostkey_ec.ser`），否则会继续加载旧算法密钥

[客户端公钥认证依赖 eddsa]
- Date: 2026-10-26
- Context: 实例切换 publickey 认证时需要解析用户的 Ed25519 公钥
- Category: Build Methods
- Instructions:
  - 在 fabric/neoforge 的 implementation 与 shadow 中都声明 `net.i2p.crypto:eddsa:0.3.0`
  - `net.i2p.crypto` 不要重定位：MINA 通过固定类名 `net.i2p.crypto.eddsa.EdDSASecurityProvider` 反射加载该提供者
