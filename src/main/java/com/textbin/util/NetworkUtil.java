package com.textbin.util;

import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

public class NetworkUtil {

    private static String cachedIp = null;

    /**
     * Resolves the machine's primary local network IPv4 address (e.g. 192.168.1.x, 10.x.x.x),
     * intelligently filtering out virtual adapters (VirtualBox, VMware, vEthernet, Hyper-V, WSL, Docker)
     * so smartphones, laptops, and tablets on the same Wi-Fi network can connect.
     */
    public static String getLocalNetworkIp() {
        if (cachedIp != null && !isVirtualOrLoopback(cachedIp)) {
            return cachedIp;
        }

        // ── Strategy 1: Active Routing Socket (Gold standard across OSes) ───────
        // Queries the OS routing table to find the interface used to reach the default gateway.
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName("8.8.8.8"), 10002);
            InetAddress localAddr = socket.getLocalAddress();
            if (localAddr instanceof Inet4Address && !localAddr.isLoopbackAddress()) {
                String ip = localAddr.getHostAddress();
                if (!isVirtualOrLoopback(ip)) {
                    cachedIp = ip;
                    return cachedIp;
                }
            }
        } catch (Exception ignored) {}

        // ── Strategy 2: Interface Enumeration with Virtual Adapter Filtering ────
        try {
            List<String> wifiCandidates = new ArrayList<>();
            List<String> lanCandidates = new ArrayList<>();
            List<String> fallbackCandidates = new ArrayList<>();

            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp() || iface.isVirtual()) continue;

                String name = (iface.getName() + " " + iface.getDisplayName()).toLowerCase();

                // Skip known virtual/host-only network adapters
                if (name.contains("virtualbox") || name.contains("vbox") || name.contains("vmware")
                        || name.contains("vethernet") || name.contains("hyper-v") || name.contains("wsl")
                        || name.contains("docker") || name.contains("teredo") || name.contains("bluetooth")
                        || name.contains("npcap") || name.contains("tap") || name.contains("vpn")
                        || name.contains("host-only")) {
                    continue;
                }

                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && addr.isSiteLocalAddress()) {
                        String ip = addr.getHostAddress();
                        if (isVirtualOrLoopback(ip)) continue;

                        if (name.contains("wi-fi") || name.contains("wifi") || name.contains("wlan") || name.contains("wireless")) {
                            wifiCandidates.add(ip);
                        } else if (name.contains("ethernet") || name.contains("eth") || name.contains("en")) {
                            lanCandidates.add(ip);
                        } else {
                            fallbackCandidates.add(ip);
                        }
                    }
                }
            }

            // Prioritize Wi-Fi first (since mobile devices connect via Wi-Fi)
            if (!wifiCandidates.isEmpty()) {
                cachedIp = wifiCandidates.get(0);
                return cachedIp;
            }
            if (!lanCandidates.isEmpty()) {
                cachedIp = lanCandidates.get(0);
                return cachedIp;
            }
            if (!fallbackCandidates.isEmpty()) {
                cachedIp = fallbackCandidates.get(0);
                return cachedIp;
            }
        } catch (Exception ignored) {}

        cachedIp = "localhost";
        return cachedIp;
    }

    /**
     * Checks if an IP belongs to a known host-only virtual adapter or loopback.
     */
    private static boolean isVirtualOrLoopback(String ip) {
        if (ip == null || ip.isBlank()) return true;
        // 192.168.56.x is standard VirtualBox Host-Only subnet
        return ip.startsWith("192.168.56.")
                || ip.startsWith("127.")
                || ip.startsWith("169.254.")
                || "localhost".equalsIgnoreCase(ip);
    }
}
