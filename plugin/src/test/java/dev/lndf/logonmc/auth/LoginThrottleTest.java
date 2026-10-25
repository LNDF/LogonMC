package dev.lndf.logonmc.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.lndf.logonmc.config.PluginConfig;
import java.net.InetAddress;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LoginThrottleTest {

    private PluginConfig.Lockout settings = new PluginConfig.Lockout(3, 5, Duration.ofMinutes(15));
    private final LoginThrottle throttle = new LoginThrottle(() -> settings);

    private static InetAddress ip(String address) throws Exception {
        return InetAddress.getByName(address);
    }

    @Test
    void locksOutIpAfterLimit() throws Exception {
        UUID account = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            assertFalse(throttle.isLockedOut(account, ip("10.0.0.1")));
            throttle.recordFailure(UUID.randomUUID(), ip("10.0.0.1"));
        }
        assertTrue(throttle.isLockedOut(account, ip("10.0.0.1")));
        assertFalse(throttle.isLockedOut(account, ip("10.0.0.2")));
    }

    @Test
    void locksOutAccountAcrossIps() throws Exception {
        UUID account = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            throttle.recordFailure(account, ip("10.0.1." + i));
        }
        assertTrue(throttle.isLockedOut(account, ip("10.0.2.1")));
        assertFalse(throttle.isLockedOut(UUID.randomUUID(), ip("10.0.2.1")));
    }

    @Test
    void ipv6CountsPerPrefix() throws Exception {
        for (int i = 1; i <= 3; i++) {
            throttle.recordFailure(UUID.randomUUID(), ip("2001:db8:1:2::" + i));
        }
        assertTrue(throttle.isLockedOut(UUID.randomUUID(), ip("2001:db8:1:2::ffff")));
        assertFalse(throttle.isLockedOut(UUID.randomUUID(), ip("2001:db8:1:3::1")));
    }

    @Test
    void successClearsOnlyTheAccount() throws Exception {
        UUID account = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            throttle.recordFailure(account, ip("10.0.3." + (i % 2)));
        }
        throttle.recordSuccess(account);
        assertFalse(throttle.isLockedOut(account, ip("10.0.4.1")));
        assertTrue(throttle.isLockedOut(account, ip("10.0.3.0")));
    }

    @Test
    void disabledLimitsAndExpiry() throws Exception {
        UUID account = UUID.randomUUID();
        for (int i = 0; i < 10; i++) {
            throttle.recordFailure(account, ip("10.0.5.1"));
        }
        settings = new PluginConfig.Lockout(0, 0, Duration.ofMinutes(15));
        assertFalse(throttle.isLockedOut(account, ip("10.0.5.1")));
        settings = new PluginConfig.Lockout(3, 5, Duration.ZERO);
        assertFalse(throttle.isLockedOut(account, ip("10.0.5.1")));
    }
}
