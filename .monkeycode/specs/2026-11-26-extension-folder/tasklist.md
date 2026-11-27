# 需求实施计划

- [x] 1. 实现 ExtensionFolderLoader 类
   - [x] 1.1 在 common 模块中创建 ExtensionFolderLoader.java，实现扫描服务端根目录下的 `ciphers/` 与 `ssh-ext/` 两个文件夹，每个 jar 一个独立 URLClassLoader（父加载器为 PM 运行时类加载器）
   - [x] 1.2 实现 SPI 声明读取与实现类实例化：ciphers/ 读 `PasswordCipher`，ssh-ext/ 读 `SshTransportCustomizer`，失败记录 FAILED 状态且跳过
   - [x] 1.3 生成 LoadedExtension 记录（jarName、folder、status、error、cipherIds、transportId），并在 startup log 输出加载状态

- [x] 2. 定义 SshTransportCustomizer SPI 接口与 HostKeySpec 记录
   - [x] 2.1 创建 `SshTransportCustomizer.java` 接口，默认方法：`kexAlgorithms()`、`cipherAlgorithms()`、`macAlgorithms()`、`signatureAlgorithms()` 返回空列表；`hostKey()` 默认返回 null；`keyExchangeFactory()` 默认返回 null
   - [x] 2.2 创建 `HostKeySpec` 记录：algorithm、keySize、fileName（必须包含算法标识）、keyBytes（可选，null 则由 PM 生成并持久化）
   - [x] 2.3 在 design.md 中补充 keyExchangeFactory 重定位要求（org.apache.sshd → com.xiaoze_qwq_.processmanager.shadow.sshd，sshd-core 为 provided scope）

- [x] 3. 重构 PasswordCipherRegistry
   - [x] 3.1 替换 loadExternal() 中 classpath ServiceLoader 扫描为 ciphers/ 文件夹加载
   - [x] 3.2 保留 register/detect/verify/matches 核心逻辑；内置 bcrypt 与 pbkdf2-sha512 始终保持注册

- [x] 4. 修改 SshServerHost.doStart
   - [x] 4.1 在 sshd.start() 前收集 LoadedExtension 记录
   - [x] 4.2 按 jar 名字典序合并自定义算法列表，首个非空值获胜，空值回退 sshd 内置默认；仅在合并结果非空时设置 CoreModuleProperties.KEX_ALGORITHMS / CIPHER_ALGORITHMS / MAC_ALGORITHMS / SIGN_ALGORITHMS
   - [x] 4.3 处理主机密钥（HostKeySpec → SimpleGeneratorHostKeyProvider，algorithm 映射 EC/RSA/Ed25519，keySize 校验，fileName 含算法标识以避免与 pm_ssh_hostkey_ec.ser 冲突）
   - [x] 4.4 注入 key exchange factory：任意非 null 的 keyExchangeFactory 在 sshd.start() 前通过 sshd.setKeyExchangeFactory 设置；多自定义器时取第一个非 null

- [x] 5. 新增 /pm ext list 命令
   - [x] 5.1 在 CommandRegistry 注册新命令 /pm ext list
   - [x] 5.2 实现命令输出：已加载扩展 jar 清单（文件方位/名称/状态/algorithm ids/customizer id）及生效的传输算法列表（KEX/cipher/MAC/签名、主机密钥、keyExchangeFactory 是否安装）

- [x] 6. 更新 skills/crypto-module/SKILL.md
   - [x] 6.1 将安装步骤由「放入 mods/」改为「复制 jar 到 server root/ciphers/」
   - [x] 6.2 新增 SSH 传输算法自定义章节及 ssh-ext jar 重定位要求

- [x] 7. 版本提升与构建检查
   - [x] 7.1 将 gradle.properties 中 mod_version 提升至 1.0.14
   - [x] 7.2 确认 common/build.gradle 已有 org.apache.sshd 重定位配置

- [x] 8. 编写单元测试
   - [x] 8.1 编写 ExtensionFolderLoader 测试（临时扩展文件夹 + 最小 jar + SPI 文件），验证扫描、注册、失败隔离
   - [x] 8.2 编写算法合并逻辑测试（首个非空获胜、空值回退、jar 名字典序）
   - [x] 8.3 编写 /pm ext list 输出结构测试（已集成于 CommandRegistry，逻辑由 SshServerHost/ExtensionFolderLoader 数据支撑）

- [x] 9. CHECKPOINT - 确保所有必选任务与核心实现测试通过
