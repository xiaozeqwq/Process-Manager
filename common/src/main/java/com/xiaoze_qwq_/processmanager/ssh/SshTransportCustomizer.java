package com.xiaoze_qwq_.processmanager.ssh;

import java.util.List;

/**
 * SSH 传输层自定义 SPI 接口。扩展 jar 通过实现该接口，在内嵌 SSH 服务启动前，
 * 提供以下可自定义项：
 * <ul>
 *   <li>{@link #kexAlgorithms()} - 密钥交换算法名称列表，将插入到 KEX 提议列表开头</li>
 *   <li>{@link #cipherAlgorithms()} - 会话加密算法名称列表，将插入到提议列表开头</li>
 *   <li>{@link #macAlgorithms()} - 消息认证码算法名称列表，将插入到提议列表开头</li>
 *   <li>{@link #signatureAlgorithms()} - 签名算法名称列表，将插入到提议列表开头</li>
 *   <li>{@link #hostKey()} - 自定义主机密钥提供者规范；返回 null 则保持内置 EC‑256 提供者</li>
 *   <li>{@link #keyExchangeFactory()} - 预留注入点（当前 sshd 2.13.2 不暴露可设置的 KeyExchangeFactory，
 *       真正的全新 KEX 实现（例如后量子 KEX）需要升级 sshd-core 版本；此处先保留接口签名，方便未来迁移）</li>
 * </ul>
 *
 * 所有默认方法均返回“空/空值”状态，即扩展仅覆盖它关心的那些项。
 */
public interface SshTransportCustomizer {

    /** 该自定义器的唯一标识符。扩展应返回一个稳定的字符串，用于日志与去重。 */
    String id();

    /** 返回要插入到 KEX 协商列表开头的算法名称列表。默认返回空列表。 */
    default List<String> kexAlgorithms() { return List.of(); }

    /** 返回要插入到会话加密套件列表开头的算法名称列表。默认返回空列表。 */
    default List<String> cipherAlgorithms() { return List.of(); }

    /** 返回要插入到 MAC 列表开头的算法名称列表。默认返回空列表。 */
    default List<String> macAlgorithms() { return List.of(); }

    /** 返回要插入到签名（公钥）算法列表开头的算法名称列表。默认返回空列表。 */
    default List<String> signatureAlgorithms() { return List.of(); }

    /** 返回自定义主机密钥规范。返回 null 则保持内置 EC‑256 提供者（pm_ssh_hostkey_ec.ser）。 */
    default HostKeySpec hostKey() { return null; }

    /** 预留接口：当前 sshd 2.13.2 不支持设置自定义 KeyExchangeFactory，真正的全新 KEX 实现
     *  （例如后量子 KEX）需要升级 sshd-core 版本；此处先不暴露该方法，方便未来迁移。 */
}