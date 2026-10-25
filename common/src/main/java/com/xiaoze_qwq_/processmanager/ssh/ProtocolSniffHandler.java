package com.xiaoze_qwq_.processmanager.ssh;

import com.xiaoze_qwq_.processmanager.ProcessManager;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 位于 Minecraft 连接管道最前端的首包嗅探器：
 * 连接首包以 "SSH-" 开头时判定为 SSH 会话，以非阻塞方式桥接到本机回环上的内嵌 SSH 服务端；
 * 其余连接原样交给 Minecraft 原有的处理器，不做任何改动。
 */
public class ProtocolSniffHandler extends ChannelInboundHandlerAdapter {
    private static final byte[] SSH_PREFIX = new byte[]{'S', 'S', 'H', '-'};
    private static final int CONNECT_TIMEOUT_MILLIS = 5000;
    private static final long SNIFF_TIMEOUT_SECONDS = 10L;

    private enum State { UNDECIDED, MINECRAFT, SSH }

    private final int sshPort;
    private final ByteArrayOutputStream peek = new ByteArrayOutputStream(4);
    private State state = State.UNDECIDED;
    private volatile boolean closed;
    private boolean primed;

    private Channel sshChannel;
    private ScheduledFuture<?> sniffTimeout;
    private String bridgeIp;
    private int bridgeLocalPort = -1;
    private final Deque<ByteBuf> pendingToSsh = new ArrayDeque<>();

    public ProtocolSniffHandler(int sshPort) {
        this.sshPort = sshPort;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        ctx.channel().config().setAutoRead(false);
        sniffTimeout = ctx.executor().schedule(() -> {
            if (state == State.UNDECIDED) {
                ctx.close();
            }
        }, SNIFF_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (ctx.channel().isActive()) {
            primeRead(ctx);
        }
        super.handlerAdded(ctx);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        primeRead(ctx);
        ctx.fireChannelActive();
    }

    private void primeRead(ChannelHandlerContext ctx) {
        if (primed) return;
        primed = true;
        if (ctx.channel().isActive()) {
            ctx.read();
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof ByteBuf buf)) {
            ctx.fireChannelRead(msg);
            return;
        }
        if (state == State.MINECRAFT) {
            ctx.fireChannelRead(buf);
            return;
        }
        if (state == State.SSH) {
            forwardToSsh(ctx, buf);
            return;
        }

        int readable = buf.readableBytes();
        byte[] data = new byte[readable];
        buf.readBytes(data);
        buf.release();
        peek.write(data, 0, data.length);

        byte[] head = peek.toByteArray();
        if (head.length < SSH_PREFIX.length) {
            if (couldBeSshPrefix(head)) {
                ctx.read();
            } else {
                switchToMinecraft(ctx, head);
            }
            return;
        }
        if (startsWith(head, SSH_PREFIX)) {
            state = State.SSH;
            startSshBridge(ctx, head);
        } else {
            switchToMinecraft(ctx, head);
        }
    }

    private void switchToMinecraft(ChannelHandlerContext ctx, byte[] head) {
        state = State.MINECRAFT;
        cancelSniffTimeout();
        ctx.channel().config().setAutoRead(true);
        ctx.fireChannelRead(Unpooled.wrappedBuffer(head));
    }

    private void startSshBridge(ChannelHandlerContext ctx, byte[] head) {
        cancelSniffTimeout();
        String ip = remoteIp(ctx);
        if (!SshGuard.tryAcquireBridge(ip)) {
            SshAudit.bridgeRejected(ip, "bridge_limit");
            ctx.close();
            return;
        }
        bridgeIp = ip;
        removeMinecraftCodecs(ctx.pipeline());

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(ctx.channel().eventLoop())
                .channel(ctx.channel().getClass())
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new SshInboundHandler(ctx));
                    }
                });

        try {
            bootstrap.connect("127.0.0.1", sshPort).addListener((ChannelFutureListener) future -> {
                if (closed || !ctx.channel().isActive()) {
                    future.channel().close();
                    return;
                }
                if (!future.isSuccess()) {
                    SshAudit.bridgeRejected(ip, "connect_failed");
                    ctx.close();
                    return;
                }
                Channel channel = future.channel();
                sshChannel = channel;
                SocketAddress local = channel.localAddress();
                if (local instanceof InetSocketAddress inet) {
                    bridgeLocalPort = inet.getPort();
                    SshBridgeRegistry.register(bridgeLocalPort, ip);
                }
                channel.writeAndFlush(Unpooled.wrappedBuffer(head)).addListener((ChannelFutureListener) write -> {
                    if (!write.isSuccess()) {
                        closeAll(ctx);
                        return;
                    }
                    drainPending(ctx);
                    ctx.read();
                });
            });
        } catch (Throwable t) {
            SshAudit.bridgeRejected(ip, "connect_error");
            releaseBridge();
            ctx.close();
        }
    }

    private void forwardToSsh(ChannelHandlerContext ctx, ByteBuf buf) {
        Channel target = sshChannel;
        if (target == null) {
            pendingToSsh.add(buf);
            return;
        }
        if (!target.isActive()) {
            buf.release();
            ctx.close();
            return;
        }
        target.writeAndFlush(buf).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                closeAll(ctx);
            } else if (!closed) {
                ctx.read();
            }
        });
    }

    private void drainPending(ChannelHandlerContext ctx) {
        Channel target = sshChannel;
        if (target == null) {
            releasePending();
            return;
        }
        ByteBuf buf;
        while ((buf = pendingToSsh.poll()) != null) {
            target.writeAndFlush(buf).addListener((ChannelFutureListener) future -> {
                if (!future.isSuccess()) {
                    closeAll(ctx);
                }
            });
        }
    }

    private void removeMinecraftCodecs(ChannelPipeline pipeline) {
        for (String name : new String[]{"timeout", "legacy_query", "splitter", "decoder", "prepender", "encoder"}) {
            try {
                if (pipeline.get(name) != null) {
                    pipeline.remove(name);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        Channel ssh = sshChannel;
        if (ssh != null) {
            ssh.config().setAutoRead(ctx.channel().isWritable());
        }
        ctx.fireChannelWritabilityChanged();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        closed = true;
        cancelSniffTimeout();
        closeSsh();
        releaseBridge();
        ctx.fireChannelInactive();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        closed = true;
        cancelSniffTimeout();
        closeSsh();
        releaseBridge();
        super.handlerRemoved(ctx);
    }

    private void releaseBridge() {
        String ip = bridgeIp;
        bridgeIp = null;
        if (ip != null) {
            SshGuard.releaseBridge(ip);
        }
    }

    private void cancelSniffTimeout() {
        ScheduledFuture<?> timeout = sniffTimeout;
        sniffTimeout = null;
        if (timeout != null) {
            timeout.cancel(false);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        closed = true;
        closeSsh();
        ctx.close();
    }

    private void closeAll(ChannelHandlerContext ctx) {
        closed = true;
        closeSsh();
        ctx.close();
    }

    private void closeSsh() {
        int port = bridgeLocalPort;
        bridgeLocalPort = -1;
        if (port > 0) {
            SshBridgeRegistry.unregister(port);
        }
        releasePending();
        Channel channel = sshChannel;
        sshChannel = null;
        if (channel != null) {
            try {
                channel.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private void releasePending() {
        ByteBuf buf;
        while ((buf = pendingToSsh.poll()) != null) {
            buf.release();
        }
    }

    private static String remoteIp(ChannelHandlerContext ctx) {
        SocketAddress address = ctx.channel().remoteAddress();
        if (address instanceof InetSocketAddress inet && inet.getAddress() != null) {
            return inet.getAddress().getHostAddress();
        }
        return "unknown";
    }

    private static boolean couldBeSshPrefix(byte[] data) {
        int length = Math.min(data.length, SSH_PREFIX.length);
        for (int i = 0; i < length; i++) {
            if (data[i] != SSH_PREFIX[i]) return false;
        }
        return true;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }

    private static final class SshInboundHandler extends ChannelInboundHandlerAdapter {
        private final ChannelHandlerContext clientCtx;

        private SshInboundHandler(ChannelHandlerContext clientCtx) {
            this.clientCtx = clientCtx;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (!(msg instanceof ByteBuf buf)) {
                ctx.fireChannelRead(msg);
                return;
            }
            if (!clientCtx.channel().isActive()) {
                buf.release();
                return;
            }
            clientCtx.writeAndFlush(buf).addListener((ChannelFutureListener) future -> {
                if (!future.isSuccess()) {
                    ctx.close();
                    clientCtx.close();
                }
            });
            if (!clientCtx.channel().isWritable()) {
                ctx.channel().config().setAutoRead(false);
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            clientCtx.close();
            ctx.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ProcessManager.LOGGER.debug("SSH bridge connection error", cause);
            ctx.close();
            clientCtx.close();
        }
    }
}
