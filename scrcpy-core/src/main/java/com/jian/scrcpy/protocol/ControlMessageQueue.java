package com.jian.scrcpy.protocol;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * scrcpy-compatible outbound control-message queue.
 *
 * <p>Matches the v5.0.1 client controller policy: regular control messages are
 * dropped once 60 messages are waiting, while UHID_CREATE and UHID_DESTROY are
 * never intentionally dropped. RESIZE_DISPLAY is coalesced separately so that
 * rapid window resizing only sends the newest size.</p>
 */
public final class ControlMessageQueue extends ChannelDuplexHandler {
    public static final int QUEUE_LIMIT = 60;

    private final Deque<ControlMessages.Message> queue = new ArrayDeque<>();
    private ChannelHandlerContext ctx;
    private ControlMessages.ResizeDisplay pendingResize;
    private boolean closed;

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        drain();
        super.channelActive(ctx);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        drain();
        super.channelWritabilityChanged(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        closed = true;
        queue.clear();
        pendingResize = null;
        super.channelInactive(ctx);
    }

    public boolean submit(ControlMessages.Message message) {
        Objects.requireNonNull(message, "message");
        ChannelHandlerContext current = ctx;
        if (current == null || closed || !current.channel().isActive()) {
            return false;
        }

        if (current.executor().inEventLoop()) {
            enqueue(message);
        } else {
            current.executor().execute(() -> enqueue(message));
        }
        return true;
    }

    private void enqueue(ControlMessages.Message message) {
        if (closed || ctx == null || !ctx.channel().isActive()) {
            return;
        }

        if (message instanceof ControlMessages.ResizeDisplay resize) {
            // Same behavior as scrcpy controller: keep only the latest resize.
            pendingResize = resize;
        } else if (queue.size() < QUEUE_LIMIT || !isDroppable(message)) {
            queue.addLast(message);
        }
        // Otherwise discard the droppable event, exactly like scrcpy.

        drain();
    }

    private void drain() {
        if (ctx == null || closed || !ctx.channel().isActive()) {
            return;
        }

        boolean wrote = false;
        while (ctx.channel().isWritable()) {
            ControlMessages.Message message;
            if (pendingResize != null) {
                message = pendingResize;
                pendingResize = null;
            } else {
                message = queue.pollFirst();
            }

            if (message == null) {
                break;
            }

            ctx.write(message, ctx.voidPromise());
            wrote = true;
        }

        if (wrote) {
            ctx.flush();
        }
    }

    /** Remove pending droppable input/events while preserving UHID lifecycle messages. */
    public void clearDroppable() {
        Runnable action = () -> {
            queue.removeIf(ControlMessageQueue::isDroppable);
            pendingResize = null;
            drain();
        };
        ChannelHandlerContext current = ctx;
        if (current == null || closed) return;
        if (current.executor().inEventLoop()) action.run();
        else current.executor().execute(action);
    }

    public int queuedMessages() {
        return queue.size() + (pendingResize == null ? 0 : 1);
    }

    public static boolean isDroppable(ControlMessages.Message message) {
        // scrcpy v5.0.1 control_msg.c: UHID create/destroy must preserve state.
        return !(message instanceof ControlMessages.UhidCreate)
                && !(message instanceof ControlMessages.UhidDestroy);
    }
}
