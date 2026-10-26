package dev.lndf.logonmc.util;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.HexFormat;

public final class Ips {

    private Ips() {
    }

    /** IPv6 is keyed by /64: one client usually controls a whole /64 and could rotate through it. */
    public static String limitKey(InetAddress address) {
        if (address instanceof Inet6Address) {
            byte[] bytes = address.getAddress();
            return HexFormat.of().formatHex(bytes, 0, 8) + "/64";
        }
        return address.getHostAddress();
    }
}
