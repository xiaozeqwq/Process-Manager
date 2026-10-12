package com.xiaoze_qwq_.processmanager.ssh;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.server.network.ServerConnectionListener;

import java.util.List;
import java.util.Map;

/**
 * 在 Minecraft 服务端监听通道的管道中，于 {@code ServerBootstrapAcceptor} 之前插入一个安装器。
 * 每当有新连接被 accept 时，安装器会先向该子通道管道最前端加入 {@link ProtocolSniffHandler}，
 * 从而在 Minecraft 的解码器读取字节之前完成协议嗅探。
 */
public final class ListenerHook {
    private static final String ACCEPTOR_CLASS = "io.netty.bootstrap.ServerBootstrap$ServerBootstrapAcceptor";
    private static final String INSTALLER_NAME = "pm_sniff_installer";
    private static final String SNIFF_NAME = "pm_sniff";

    private ListenerHook() {
    }

    public static int install(ServerConnectionListener listener, int sshPort) {
        List<ChannelFuture> channels = listener.channels;
        if (channels == null) return 0;

        int installed = 0;
        for (ChannelFuture future : channels) {
            Channel serverChannel = future.channel();
            if (serverChannel == null || !serverChannel.isOpen()) continue;
            if (serverChannel.pipeline().get(INSTALLER_NAME) != null) continue;

            String acceptorName = findAcceptorName(serverChannel);
            if (acceptorName == null) continue;

            serverChannel.pipeline().addBefore(acceptorName, INSTALLER_NAME, new Installer(sshPort));
            installed++;
        }
        return installed;
    }

    private static String findAcceptorName(Channel serverChannel) {
        for (Map.Entry<String, ChannelHandler> entry : serverChannel.pipeline()) {
            if (entry.getValue().getClass().getName().equals(ACCEPTOR_CLASS)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static final class Installer extends ChannelInboundHandlerAdapter {
        private final int sshPort;

        Installer(int sshPort) {
            this.sshPort = sshPort;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof Channel) {
                Channel child = (Channel) msg;
                if (child.pipeline().get(SNIFF_NAME) == null) {
                    child.pipeline().addFirst(SNIFF_NAME, new ProtocolSniffHandler(sshPort));
                }
            }
            ctx.fireChannelRead(msg);
        }
    }
}
