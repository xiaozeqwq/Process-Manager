package com.xiaoze_qwq_.processmanager.ssh;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 统一的 SSH 审计日志。仅记录事件、来源 IP、实例 ID 与原因，
 * 不记录用户名、密码或客户端输入内容。
 */
public final class SshAudit {
    private static final Logger LOGGER = LoggerFactory.getLogger("PM-SSH");

    private SshAudit() {
    }

    public static void authOk(String ip, String instanceId) {
        LOGGER.info("PM-SSH event=auth_ok ip={} instance={}", ip, safe(instanceId));
    }

    public static void authFail(String ip, String instanceId, String reason) {
        LOGGER.warn("PM-SSH event=auth_fail ip={} instance={} reason={}", ip, safe(instanceId), safe(reason));
    }

    public static void bridgeRejected(String ip, String reason) {
        LOGGER.warn("PM-SSH event=bridge_rejected ip={} reason={}", safe(ip), safe(reason));
    }

    public static void sessionClosed(String ip, String instanceId, long durationMillis) {
        LOGGER.info("PM-SSH event=session_close ip={} instance={} duration_ms={}",
                safe(ip), safe(instanceId), durationMillis);
    }

    /** 菜单操作审计：仅记录动作类型与目标实例，不记录命令内容。 */
    public static void menuAction(String ip, String instanceId, String action) {
        LOGGER.info("PM-SSH event=menu_action ip={} instance={} action={}",
                safe(ip), safe(instanceId), safe(action));
    }

    private static String safe(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }
}
