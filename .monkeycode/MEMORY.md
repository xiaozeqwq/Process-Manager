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
  - SSH 用户名完全忽略，不映射、不查询、不暴露 Linux/Windows 用户名；仅用唯一启用实例的密码认证
  - 全局同时只允许一个实例启用 SSH，启用前实例必须处于停止状态
  - 不读取系统 authorized_keys，不提供 shell、命令执行、SFTP/SCP、端口转发

[每次更新必须提升版本]
- Date: 2026-10-25
- Context: 用户明确要求每次更新都要提升版本并重新构建
- Category: Build Methods
- Instructions:
  - 每次修改合入 `master` 前必须提升 `gradle.properties` 中的 `mod_version`
  - 版本提升后通过 GitHub Actions 重新构建，并发布对应版本 tag 的 Release
  - 禁止复用已存在的版本号发布新构建，避免 Release tag 指向旧提交

[模组 fat jar 必须隔离所有第三方依赖]
- Date: 2026-10-25
- Context: 用户报告服务器启动时因模组内置 JNA 未重定位导致模块包冲突
- Category: Build Methods
- Instructions:
  - `shadowJar` 必须重定位所有 bundled 第三方包，至少包括 `com.sun.jna`、`com.pty4j`、`org.jetbrains.annotations`、`org.intellij.lang.annotations` 及已有的 sshd/commons/toml/gson/bcrypt
  - pty4j 原生库资源路径 `resources/com/pty4j/native` 也必须通过 Shadow relocate 改写，避免与其他模组共享同名资源
  - JNA 原生库位于 `com/sun/jna/<os>-<arch>/`，随包名 relocate 一起改写
  - 新增依赖后先检查产物中是否仍存在未重定位的第三方包根目录
