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
- Date: 2026-10-11
- Context: 用户在处理 Architectury 模组重命名任务时明确提出
- Instructions:
  - 思考过程与对话回复一律使用中文
  - 后续所有任务均遵循此约定

[编译使用 GitHub Actions]
- Date: 2026-10-11
- Context: 用户指出本项目的编译验证一贯通过 GitHub Actions 完成
- Instructions:
  - 不要在本机安装 JDK 或执行本地 gradle 构建
  - 编译验证通过 push 分支并触发 GitHub Actions workflow 完成
  - workflow 中的 jar 名等应从 gradle.properties 动态读取（如 mod_id、mod_version），避免硬编码
