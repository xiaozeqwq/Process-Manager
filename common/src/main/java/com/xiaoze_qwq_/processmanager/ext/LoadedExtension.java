package com.xiaoze_qwq_.processmanager.ext;

import java.util.List;

/**
 * 单个扩展 jar 的加载结果记录（仅内存，不落盘）。
 *
 * @param jarName     jar 文件名（不含路径）
 * @param folder      所在扩展文件夹名（ciphers 或 ssh-ext）
 * @param status      加载状态
 * @param error       失败原因，成功时为 null
 * @param cipherIds   注册的密码哈希算法 id（仅 ciphers 文件夹填充）
 * @param transportId 传输自定义器 id（仅 ssh-ext 文件夹填充，无自定义器时为 null）
 */
public record LoadedExtension(
        String jarName,
        String folder,
        ExtensionStatus status,
        String error,
        List<String> cipherIds,
        String transportId) {
}