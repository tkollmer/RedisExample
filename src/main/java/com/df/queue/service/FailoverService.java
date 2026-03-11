package com.df.queue.service;

import com.df.queue.web.SignalWebSocketHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages active/standby failover between two app instances.
 *
 * <p>Heartbeats the peer every 1s. If the peer is unreachable for 3s
 * and this instance is standby, it promotes itself to active.
 * Split-brain resolution: lower instance ID wins.
 *
 * <p>On startup as standby, syncs state from the active peer.
 * SignalGenerator and QueueService check {@link #isActive()} to gate their behavior.
 */
@Service
public class FailoverService {

    private static final Logger log = LoggerFactory.getLogger(FailoverService.class);

    private final RestTemplate restTemplate;
    private final SignalWebSocketHandler wsHandler;
    private final QueueService queueService;

    @Value("${failover.instance-id:app-a}")
    private String instanceId;

    @Value("${failover.instance-role:active}")
    private String initialRole;

    @Value("${failover.peer-url:http://app-b:8080}")
    private String peerUrl;

    private final AtomicBoolean active = new AtomicBoolean(false);
    private long lastPeerContact = System.currentTimeMillis();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public FailoverService(RestTemplate restTemplate, SignalWebSocketHandler wsHandler,
                           @Lazy QueueService queueService) {
        this.restTemplate = restTemplate;
        this.wsHandler = wsHandler;
        this.queueService = queueService;
    }

    @PostConstruct
    public void start() {
        boolean isActive = "active".equalsIgnoreCase(initialRole);
        active.set(isActive);

        if (!isActive) {
            queueService.setBroadcastEnabled(false);
            // Sync state from active peer on startup
            scheduler.schedule(this::syncFromPeer, 3, TimeUnit.SECONDS);
        }

        log.info("Instance {} starting as {}, peer: {}", instanceId, isActive ? "ACTIVE" : "STANDBY", peerUrl);
        broadcastStatus();

        scheduler.scheduleAtFixedRate(this::heartbeat, 1, 1, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }

    public boolean isActive() {
        return active.get();
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String getPeerUrl() {
        return peerUrl;
    }

    private void syncFromPeer() {
        try {
            log.info("Syncing state from peer {}", peerUrl);
            ResponseEntity<List<Map<String, String>>> response = restTemplate.exchange(
                    peerUrl + "/api/sync/snapshot",
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<List<Map<String, String>>>() {}
            );
            List<Map<String, String>> snapshot = response.getBody();
            if (snapshot != null && !snapshot.isEmpty()) {
                queueService.restoreFromSnapshot(snapshot);
                log.info("Synced {} entities from peer", snapshot.size());
            } else {
                log.info("Peer returned empty snapshot");
            }
        } catch (Exception e) {
            log.warn("Failed to sync from peer: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void heartbeat() {
        try {
            Map<String, Object> response = restTemplate.getForObject(peerUrl + "/api/health", Map.class);
            if (response != null) {
                lastPeerContact = System.currentTimeMillis();

                Boolean peerActive = (Boolean) response.get("active");
                String peerId = (String) response.get("instanceId");

                if (peerActive != null && peerActive && active.get()) {
                    if (instanceId.compareTo(peerId) > 0) {
                        log.warn("Split-brain detected! {} demoting to standby (peer {} has priority)",
                                instanceId, peerId);
                        demote();
                    }
                }
            }
        } catch (Exception e) {
            long downTime = System.currentTimeMillis() - lastPeerContact;
            if (!active.get() && downTime > 3000) {
                log.info("Peer unreachable for {}ms, promoting {} to ACTIVE", downTime, instanceId);
                promote();
            }
        }
    }

    private void promote() {
        if (active.compareAndSet(false, true)) {
            log.info("PROMOTED {} to ACTIVE", instanceId);
            queueService.setBroadcastEnabled(true);
            broadcastStatus();
        }
    }

    private void demote() {
        if (active.compareAndSet(true, false)) {
            log.info("DEMOTED {} to STANDBY", instanceId);
            queueService.setBroadcastEnabled(false);
            broadcastStatus();
            scheduler.schedule(this::syncFromPeer, 2, TimeUnit.SECONDS);
        }
    }

    private void broadcastStatus() {
        wsHandler.broadcast("instance-status", Map.of(
                "instanceId", instanceId,
                "role", active.get() ? "active" : "standby"
        ));
    }
}
