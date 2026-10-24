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
- Date: 2026-10-11
- Context: 用户指出本项目的编译验证一贯通过 GitHub Actions 完成
- Instructions:
  - 不要在本机安装 JDK 或执行本地 gradle 构建
  - 编译验证通过 push 分支并触发 GitHub Actions workflow 完成
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
