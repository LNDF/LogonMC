package dev.lndf.logonmc;

import com.velocitypowered.api.proxy.Player;
import dev.lndf.logonmc.api.LogonMC;
import dev.lndf.logonmc.api.LogonUser;
import dev.lndf.logonmc.auth.AuthService;
import dev.lndf.logonmc.auth.PlayerAuthState;
import dev.lndf.logonmc.crypto.HashedPassword;
import dev.lndf.logonmc.db.SessionRepository;
import dev.lndf.logonmc.db.StoredUser;
import dev.lndf.logonmc.db.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

final class LogonMCImpl implements LogonMC {

    private final LogonMCPlugin plugin;
    private final AuthService auth;
    private final UserRepository users;
    private final SessionRepository sessions;

    LogonMCImpl(LogonMCPlugin plugin, AuthService auth, UserRepository users, SessionRepository sessions) {
        this.plugin = plugin;
        this.auth = auth;
        this.users = users;
        this.sessions = sessions;
    }

    @Override
    public boolean isAuthenticated(Player player) {
        return auth.isAuthenticated(player);
    }

    @Override
    public boolean isLinked(Player player) {
        PlayerAuthState state = auth.state(player);
        StoredUser user = state == null ? null : state.user();
        return player.isOnlineMode() && user != null && user.isLinked();
    }

    @Override
    public boolean isRegistered(Player player) {
        PlayerAuthState state = auth.state(player);
        return state != null && state.isRegistered();
    }

    @Override
    public CompletableFuture<Optional<LogonUser>> getUser(UUID uuid) {
        return supply(() -> users.findByUuid(uuid).map(StoredUser::toApi));
    }

    @Override
    public CompletableFuture<Optional<LogonUser>> getUser(String name) {
        return supply(() -> users.findByName(name).map(StoredUser::toApi));
    }

    @Override
    public CompletableFuture<List<LogonUser>> getUsers(String name) {
        return supply(() -> users.findAllByName(name).stream().map(StoredUser::toApi).toList());
    }

    @Override
    public CompletableFuture<Optional<LogonUser>> getUserByPremiumUuid(UUID premiumUuid) {
        return supply(() -> users.findByPremiumUuid(premiumUuid).map(StoredUser::toApi));
    }

    @Override
    public CompletableFuture<Void> forceLogin(Player player) {
        return supply(() -> {
            auth.forceLogin(player);
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> forceLogout(Player player) {
        return supply(() -> {
            auth.forceLogout(player);
            return null;
        });
    }

    @Override
    public CompletableFuture<Boolean> checkPassword(UUID uuid, String password) {
        return supply(() -> users.findByUuid(uuid)
                .map(StoredUser::password)
                .map(hash -> plugin.hashers().verify(password, hash))
                .orElse(false));
    }

    @Override
    public CompletableFuture<Boolean> setPassword(UUID uuid, String password) {
        return supply(() -> {
            Optional<StoredUser> user = users.findByUuid(uuid);
            if (user.isEmpty()) {
                return false;
            }
            HashedPassword hash = plugin.hashers().hash(password);
            user.get().password(hash);
            users.updatePassword(uuid, hash);
            sessions.delete(uuid);
            auth.refreshUser(uuid, user.get());
            return true;
        });
    }

    @Override
    public CompletableFuture<Boolean> unregister(UUID uuid) {
        return supply(() -> {
            boolean deleted = users.delete(uuid);
            if (deleted) {
                plugin.proxy().getPlayer(uuid).ifPresent(p -> p.disconnect(plugin.messages().get("kick.unregistered")));
            }
            return deleted;
        });
    }

    @Override
    public CompletableFuture<Void> invalidateSession(UUID uuid) {
        return supply(() -> {
            sessions.delete(uuid);
            return null;
        });
    }

    private <T> CompletableFuture<T> supply(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, plugin.executor());
    }
}
