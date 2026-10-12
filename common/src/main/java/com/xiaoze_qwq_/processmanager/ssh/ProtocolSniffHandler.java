package com.xiaoze_qwq_.processmanager.ssh;

import com.xiaoze_qwq_.processmanager.ProcessManager;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 位于 Minecraft 连接管道最前端的首包嗅探器：
 * 连接首包以 "SSH-" 开头时判定为 SSH 会话，桥接到本机回环上的内嵌 SSH 服务端；
 * 其余连接原样交给 Minecraft 原有的处理器，不做任何改动。
 */
public class ProtocolSniffHandler extends ChannelInboundHandlerAdapter {
    private static final byte[] SSH_PREFIX = new byte[]{'S', 'S', 'H', '-'};

    private enum State { UNDECIDED, MINECRAFT, SSH }

    private final int sshPort;
    private final ByteArrayOutputStream peek = new ByteArrayOutputStream(8);
    private volatile State state = State.UNDECIDED;
    private volatile boolean closed;

    private Socket sshSocket;
    private OutputStream sshOut;
    private Thread sshReader;

    public ProtocolSniffHandler(int sshPort) {
        this.sshPort = sshPort;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof ByteBuf)) {
            ctx.fireChannelRead(msg);
            return;
        }
        ByteBuf buf = (ByteBuf) msg;
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();

        if (state == State.MINECRAFT) {
            ctx.fireChannelRead(Unpooled.wrappedBuffer(data));
            return;
        }
        if (state == State.SSH) {
            writeToSsh(data);
            return;
        }

        peek.write(data);
        byte[] head = peek.toByteArray();
        if (head.length < SSH_PREFIX.length) {
            return;
        }
        if (startsWith(head, SSH_PREFIX)) {
            state = State.SSH;
            startSshBridge(ctx, head);
        } else {
            state = State.MINECRAFT;
            ctx.fireChannelRead(Unpooled.wrappedBuffer(head));
        }
    }

    private void startSshBridge(ChannelHandlerContext ctx, byte[] head) {
        try {
            ctx.pipeline().remove("timeout");
        } catch (Throwable ignored) {
        }
        try {
            Socket socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress("127.0.0.1", sshPort), 5000);
            this.sshSocket = socket;
            this.sshOut = socket.getOutputStream();
            this.sshOut.write(head);
            this.sshOut.flush();

            this.sshReader = new Thread(() -> pumpSshToChannel(ctx, socket), "PM-SSH-Reader");
            this.sshReader.setDaemon(true);
            this.sshReader.start();
        } catch (Throwable t) {
            ProcessManager.LOGGER.warn("Failed to bridge SSH connection to embedded server", t);
            ctx.close();
        }
    }

    private void pumpSshToChannel(ChannelHandlerContext ctx, Socket socket) {
        byte[] buf = new byte[8192];
        try {
            int n;
            while (!closed && (n = socket.getInputStream().read(buf)) >= 0) {
                ctx.channel().writeAndFlush(Unpooled.copiedBuffer(buf, 0, n));
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                ctx.channel().close();
            } catch (Throwable ignored) {
            }
        }
    }

    private void writeToSsh(byte[] data) {
        OutputStream out = sshOut;
        if (out == null) return;
        try {
            out.write(data);
            out.flush();
        } catch (Throwable t) {
            if (!closed) {
                ProcessManager.LOGGER.debug("SSH bridge write failed", t);
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        closed = true;
        closeQuietly();
        ctx.fireChannelInactive();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        closed = true;
        closeQuietly();
        super.handlerRemoved(ctx);
    }

    private void closeQuietly() {
        try {
            if (sshOut != null) sshOut.close();
        } catch (Throwable ignored) {
        }
        try {
            if (sshSocket != null) sshSocket.close();
        } catch (Throwable ignored) {
        }
        if (sshReader != null) sshReader.interrupt();
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }
}
