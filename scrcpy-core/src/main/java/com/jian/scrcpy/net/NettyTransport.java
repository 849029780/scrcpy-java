package com.jian.scrcpy.net;

import io.netty.channel.IoHandlerFactory;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.kqueue.KQueueIoHandler;
import io.netty.channel.kqueue.KQueueServerSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.uring.IoUring;
import io.netty.channel.uring.IoUringIoHandler;
import io.netty.channel.uring.IoUringServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Selects a Netty 4.2 I/O implementation and its matching server channel.
 *
 * <p>Set {@code -Dscrcpy.netty.transport=auto|io_uring|epoll|kqueue|nio}
 * to override automatic selection. An unavailable requested native transport
 * is reported and safely falls back to automatic selection/NIO.</p>
 */
public final class NettyTransport {
    private static final Logger LOG = LoggerFactory.getLogger(NettyTransport.class);
    private static final String TRANSPORT_PROPERTY = "scrcpy.netty.transport";

    private NettyTransport() {
    }

    public record Selection(
            String name,
            IoHandlerFactory ioHandlerFactory,
            Class<? extends ServerChannel> serverChannelClass) {
    }

    public static Selection select() {
        String requested = System.getProperty(TRANSPORT_PROPERTY, "auto")
                .trim().toLowerCase(java.util.Locale.ROOT);

        if (!"auto".equals(requested)) {
            Selection forced = requested(requested);
            if (forced != null) {
                LOG.info("Netty transport selected: {} (configured by {})", forced.name(), TRANSPORT_PROPERTY);
                return forced;
            }
            LOG.warn("Requested Netty transport '{}' is unavailable or unsupported; falling back to auto",
                    requested);
        }

        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("linux")) {
            Selection ioUring = availableIoUring();
            if (ioUring != null) return logSelected(ioUring);
            Selection epoll = availableEpoll();
            if (epoll != null) return logSelected(epoll);
        } else if (os.contains("mac") || os.contains("darwin") || os.contains("bsd")) {
            Selection kqueue = availableKQueue();
            if (kqueue != null) return logSelected(kqueue);
        }

        return logSelected(nio());
    }

    private static Selection requested(String name) {
        return switch (name) {
            case "nio" -> nio();
            case "io_uring", "iouring" -> availableIoUring();
            case "epoll" -> availableEpoll();
            case "kqueue" -> availableKQueue();
            default -> null;
        };
    }

    private static Selection availableIoUring() {
        try {
            if (IoUring.isAvailable()) {
                return new Selection("io_uring", IoUringIoHandler.newFactory(),
                        IoUringServerSocketChannel.class);
            }
        } catch (LinkageError | RuntimeException error) {
            LOG.debug("Netty io_uring initialization failed; trying the next transport", error);
        }
        return null;
    }

    private static Selection availableEpoll() {
        try {
            if (io.netty.channel.epoll.Epoll.isAvailable()) {
                return new Selection("epoll", EpollIoHandler.newFactory(),
                        EpollServerSocketChannel.class);
            }
        } catch (LinkageError | RuntimeException error) {
            LOG.debug("Netty epoll initialization failed; trying the next transport", error);
        }
        return null;
    }

    private static Selection availableKQueue() {
        try {
            if (io.netty.channel.kqueue.KQueue.isAvailable()) {
                return new Selection("kqueue", KQueueIoHandler.newFactory(),
                        KQueueServerSocketChannel.class);
            }
        } catch (LinkageError | RuntimeException error) {
            LOG.debug("Netty kqueue initialization failed; trying the next transport", error);
        }
        return null;
    }

    private static Selection nio() {
        return new Selection("nio", NioIoHandler.newFactory(), NioServerSocketChannel.class);
    }

    private static Selection logSelected(Selection selection) {
        LOG.info("Netty transport selected: {}", selection.name());
        return selection;
    }
}
