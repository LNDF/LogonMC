package dev.lndf.logonmc.auth;

import com.velocitypowered.api.scheduler.ScheduledTask;
import dev.lndf.logonmc.api.AuthReason;
import dev.lndf.logonmc.db.StoredUser;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * Authentication state of one online player.
 */
public final class PlayerAuthState {

    private volatile @Nullable StoredUser user;
    private volatile @Nullable AuthReason reason;
    private volatile @Nullable String lastServer;
    private final AtomicInteger failedAttempts = new AtomicInteger();
    private final AtomicBoolean premiumSuggested = new AtomicBoolean();
    private volatile @Nullable ScheduledTask reminderTask;
    private volatile @Nullable ScheduledTask timeoutTask;

    PlayerAuthState(@Nullable StoredUser user) {
        this.user = user;
        this.lastServer = user == null ? null : user.lastServer();
    }

    public @Nullable StoredUser user() {
        return user;
    }

    void user(@Nullable StoredUser user) {
        this.user = user;
    }

    public boolean isRegistered() {
        StoredUser current = user;
        return current != null && (current.hasPassword() || current.isLinked());
    }

    public boolean isAuthenticated() {
        return reason != null;
    }

    public @Nullable AuthReason reason() {
        return reason;
    }

    void reason(@Nullable AuthReason reason) {
        this.reason = reason;
    }

    public @Nullable String lastServer() {
        return lastServer;
    }

    void lastServer(@Nullable String lastServer) {
        this.lastServer = lastServer;
    }

    int recordFailure() {
        return failedAttempts.incrementAndGet();
    }

    /** True the first time it is called for this connection. */
    boolean markPremiumSuggested() {
        return premiumSuggested.compareAndSet(false, true);
    }

    void scheduleTasks(ScheduledTask reminder, ScheduledTask timeout) {
        cancelTasks();
        this.reminderTask = reminder;
        this.timeoutTask = timeout;
    }

    void cancelTasks() {
        ScheduledTask reminder = reminderTask;
        ScheduledTask timeout = timeoutTask;
        if (reminder != null) {
            reminder.cancel();
        }
        if (timeout != null) {
            timeout.cancel();
        }
        reminderTask = null;
        timeoutTask = null;
    }
}
