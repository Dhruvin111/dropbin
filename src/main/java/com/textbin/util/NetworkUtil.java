package com.textbin.util;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

public class NetworkUtil {

    private static String cachedIp = null;

    /**
     * Resolves the machine's primary local network IPv4 address (e.g. 192.168.x.x, 10.x.x.x),
     * so other devices on the LAN know where to connect.
     */
    public static String getLocalNetworkIp() {
        if (cachedIp != null) {
            return cachedIp;
        }
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp() || iface.isVirtual()) continue;
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && addr.isSiteLocalAddress()) {
                        cachedIp = addr.getHostAddress();
                        return cachedIp;
                    }
                }
            }
        } catch (Exception ignored) {}

        cachedIp = "localhost";
        return cachedIp;
    }
}
