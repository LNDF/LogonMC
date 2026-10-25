package dev.lndf.logonmc.util;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.HexFormat;

public final class Ips {

    private Ips() {
    }

    /**
     * Key for per-client limits: the address for IPv4, the /64 prefix for IPv6, since one
     * client usually controls a whole /64 and could otherwise rotate through it for free.
     */
    public static String limitKey(InetAddress address) {
        if (address instanceof Inet6Address) {
            byte[] bytes = address.getAddress();
            return HexFormat.of().formatHex(bytes, 0, 8) + "/64";
        }
        return address.getHostAddress();
    }
}
