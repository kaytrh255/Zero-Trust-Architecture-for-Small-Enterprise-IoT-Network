package com.yak.zerotrust.security;

import com.yak.zerotrust.exception.LoginRateLimitExceededException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** A bounded, single-process fixed-window limiter for authentication proofs keyed by socket peer. */
@Component
public class LoginRateLimiter {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long MAX_WINDOW_SECONDS = 24 * 60 * 60;

    private final int maxAttempts;
    private final long windowNanos;
    private final int maxTrackedClients;
    private final LinkedHashMap<String, ClientWindow> windows = new LinkedHashMap<>(16, 0.75f, true);

    public LoginRateLimiter(
            @Value("${security.login-rate-limit.max-attempts:20}") int maxAttempts,
            @Value("${security.login-rate-limit.window-seconds:60}") long windowSeconds,
            @Value("${security.login-rate-limit.max-tracked-clients:10000}") int maxTrackedClients
    ) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("Login rate-limit max-attempts must be positive");
        }
        if (windowSeconds < 1 || windowSeconds > MAX_WINDOW_SECONDS) {
            throw new IllegalArgumentException("Login rate-limit window-seconds must be between 1 and 86400");
        }
        if (maxTrackedClients < 1) {
            throw new IllegalArgumentException("Login rate-limit max-tracked-clients must be positive");
        }
        this.maxAttempts = maxAttempts;
        windowNanos = Duration.ofSeconds(windowSeconds).toNanos();
        this.maxTrackedClients = maxTrackedClients;
    }

    public synchronized void checkAndRecord(String remoteAddress) {
        long now = System.nanoTime();
        removeExpiredWindows(now);

        String clientKey = remoteAddress == null || remoteAddress.isBlank()
                ? "unknown"
                : remoteAddress.trim();
        ClientWindow window = windows.get(clientKey);
        if (window == null) {
            makeRoomIfNeeded();
            window = new ClientWindow(now + windowNanos);
            windows.put(clientKey, window);
        }

        if (window.attempts >= maxAttempts) {
            long remainingNanos = Math.max(0, window.resetAtNanos - now);
            long retryAfterSeconds = Math.max(
                    1,
                    (remainingNanos + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND
            );
            throw new LoginRateLimitExceededException(retryAfterSeconds);
        }
        window.attempts++;
    }

    private void removeExpiredWindows(long now) {
        Iterator<Map.Entry<String, ClientWindow>> iterator = windows.entrySet().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().getValue().resetAtNanos >= 0) {
                iterator.remove();
            }
        }
    }

    private void makeRoomIfNeeded() {
        if (windows.size() < maxTrackedClients) {
            return;
        }
        Iterator<String> iterator = windows.keySet().iterator();
        if (iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private static final class ClientWindow {
        private final long resetAtNanos;
        private int attempts;

        private ClientWindow(long resetAtNanos) {
            this.resetAtNanos = resetAtNanos;
        }
    }
}
