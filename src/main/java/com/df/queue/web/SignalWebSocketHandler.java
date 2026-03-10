package com.df.queue.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Asynchronous WebSocket broadcast handler with per-session message queues.
 *
 * <p>Each connected session gets a bounded queue (64 messages) and a dedicated
 * sender thread. The {@link #broadcast} method serializes once and does a
 * non-blocking {@code offer()} into each session's queue.
 *
 * <h3>Why async?</h3>
 * <p>The previous synchronous approach held a lock per session during send,
 * meaning a slow client could block the tick/detect threads. With async queues,
 * {@code broadcast()} returns immediately. If a client can't keep up, messages
 * are silently dropped (bounded queue) rather than backing up the producer.
 */
@Component
public class SignalWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SignalWebSocketHandler.class);

    /** Per-session bounded message queues. Keys are sessions, values are queues drained by sender threads. */
    private final Map<WebSocketSession, BlockingQueue<TextMessage>> sessionQueues = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Registers a new WebSocket session and starts its dedicated sender thread.
     *
     * <p>The sender thread blocks on {@code queue.take()} and sends messages
     * one at a time. It exits cleanly when the session closes or on error.
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        ArrayBlockingQueue<TextMessage> queue = new ArrayBlockingQueue<>(64);
        sessionQueues.put(session, queue);

        Thread sender = new Thread(() -> {
            while (session.isOpen()) {
                try {
                    TextMessage msg = queue.take();
                    synchronized (session) {
                        session.sendMessage(msg);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn("Send failed for {}: {}", session.getId(), e.getMessage());
                    break;
                }
            }
        }, "ws-sender-" + session.getId());
        sender.setDaemon(true);
        sender.start();

        log.info("WebSocket connected: {}", session.getId());
    }

    /**
     * Cleans up the session's queue and sender thread on disconnect.
     * The sender thread will exit naturally when it sees the session is closed.
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        BlockingQueue<TextMessage> queue = sessionQueues.remove(session);
        if (queue != null) {
            queue.clear();
        }
        log.info("WebSocket disconnected: {}", session.getId());
    }

    /**
     * Broadcasts a typed message to all connected WebSocket clients.
     *
     * <p>Serializes the payload to JSON once, then offers it to each session's
     * queue. The offer is non-blocking — if a session's queue is full (slow
     * consumer), the message is dropped for that session only.
     *
     * @param type    message type (e.g. "blocks", "entity", "merge-start")
     * @param payload data payload (serialized to JSON)
     */
    public void broadcast(String type, Object payload) {
        try {
            String json = mapper.writeValueAsString(new WsMessage(type, payload));
            TextMessage msg = new TextMessage(json);
            for (Map.Entry<WebSocketSession, BlockingQueue<TextMessage>> entry : sessionQueues.entrySet()) {
                if (entry.getKey().isOpen()) {
                    entry.getValue().offer(msg); // non-blocking, drops if queue is full
                }
            }
        } catch (Exception e) {
            log.error("Broadcast error", e);
        }
    }

    /** WebSocket message wrapper: all messages have a type and data field. */
    public record WsMessage(String type, Object data) {}
}
