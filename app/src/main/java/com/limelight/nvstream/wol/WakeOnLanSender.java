package com.limelight.nvstream.wol;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Scanner;

import com.limelight.LimeLog;
import com.limelight.nvstream.http.ComputerDetails;

public class WakeOnLanSender {
    // These ports will always be tried as-is.
    private static final int[] STATIC_PORTS_TO_TRY = new int[] {
        9, // Standard WOL port (privileged port)
        47009, // Port opened by Moonlight Internet Hosting Tool for WoL (non-privileged port)
    };

    // These ports will be offset by the base port number (47989) to support alternate ports.
    private static final int[] DYNAMIC_PORTS_TO_TRY = new int[] {
        47998, 47999, 48000, 48002, 48010, // Ports opened by GFE
    };

    private static void sendPacketsForAddress(InetAddress address, int httpPort, DatagramSocket sock, byte[] payload) throws IOException {
        IOException lastException = null;
        boolean sentWolPacket = false;

        // Try the static ports
        for (int port : STATIC_PORTS_TO_TRY) {
            try {
                DatagramPacket dp = new DatagramPacket(payload, payload.length);
                dp.setAddress(address);
                dp.setPort(port);
                sock.send(dp);
                sentWolPacket = true;
            } catch (IOException e) {
                e.printStackTrace();
                lastException = e;
            }
        }

        // Try the dynamic ports
        for (int port : DYNAMIC_PORTS_TO_TRY) {
            try {
                DatagramPacket dp = new DatagramPacket(payload, payload.length);
                dp.setAddress(address);
                dp.setPort((port - 47989) + httpPort);
                sock.send(dp);
                sentWolPacket = true;
            } catch (IOException e) {
                e.printStackTrace();
                lastException = e;
            }
        }

        if (!sentWolPacket) {
            throw lastException;
        }
    }
    
    /** The broadcast address of every IPv4 network this device is on (Wi-Fi, Ethernet), not loopback. */
    private static java.util.List<InetAddress> subnetBroadcasts() {
        java.util.List<InetAddress> out = new java.util.ArrayList<>();
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b != null && !out.contains(b)) out.add(b);
                }
            }
        } catch (Exception e) {
            LimeLog.warning("WoL: no interface list: " + e);
        }
        return out;
    }

    public static void sendWolPacket(ComputerDetails computer) throws IOException {
        byte[] payload = createWolPayload(computer);
        IOException lastException = null;
        boolean sentWolPacket = false;

        try (final DatagramSocket sock = new DatagramSocket(0)) {
            // Try all resolved remote and local addresses and broadcast addresses.
            // The broadcast address is required to avoid stale ARP cache entries
            // making the sleeping machine unreachable.
            for (ComputerDetails.AddressTuple address : new ComputerDetails.AddressTuple[] {
                    computer.localAddress, computer.remoteAddress,
                    computer.manualAddress, computer.ipv6Address,
            }) {
                if (address == null) {
                    continue;
                }

                try {
                    sendPacketsForAddress(InetAddress.getByName("255.255.255.255"), address.port, sock, payload);
                    sentWolPacket = true;
                } catch (IOException e) {
                    e.printStackTrace();
                    lastException = e;
                }

                // MoonCast: also each local network's own broadcast address (192.168.1.255 and the like). Some Wi-Fi
                // access points drop 255.255.255.255 but pass a subnet broadcast to the wired side.
                for (InetAddress bcast : subnetBroadcasts()) {
                    try {
                        sendPacketsForAddress(bcast, address.port, sock, payload);
                        sentWolPacket = true;
                    } catch (IOException e) {
                        lastException = e;
                    }
                }

                try {
                    for (InetAddress resolvedAddress : InetAddress.getAllByName(address.address)) {
                        try {
                            sendPacketsForAddress(resolvedAddress, address.port, sock, payload);
                            sentWolPacket = true;
                        } catch (IOException e) {
                            e.printStackTrace();
                            lastException = e;
                        }
                    }
                } catch (IOException e) {
                    // We may have addresses that don't resolve on this subnet,
                    // but don't throw and exit the whole function if that happens.
                    // We'll throw it at the end if we didn't send a single packet.
                    e.printStackTrace();
                    lastException = e;
                }
            }
        }

        // Propagate the DNS resolution exception if we didn't
        // manage to get a single packet out to the host.
        if (!sentWolPacket && lastException != null) {
            throw lastException;
        }
    }
    
    private static byte[] macStringToBytes(String macAddress) {
        byte[] macBytes = new byte[6];

        try (@SuppressWarnings("resource")
             final Scanner scan = new Scanner(macAddress).useDelimiter(":")
        ) {
            for (int i = 0; i < macBytes.length && scan.hasNext(); i++) {
                try {
                    macBytes[i] = (byte) Integer.parseInt(scan.next(), 16);
                } catch (NumberFormatException e) {
                    LimeLog.warning("Malformed MAC address: " + macAddress + " (index: " + i + ")");
                    break;
                }
            }
            return macBytes;
        }
    }
    
    private static byte[] createWolPayload(ComputerDetails computer) {
        byte[] payload = new byte[102];
        byte[] macAddress = macStringToBytes(computer.macAddress);
        int i;
        
        // 6 bytes of FF
        for (i = 0; i < 6; i++) {
            payload[i] = (byte)0xFF;
        }
        
        // 16 repetitions of the MAC address
        for (int j = 0; j < 16; j++) {
            System.arraycopy(macAddress, 0, payload, i, macAddress.length);
            i += macAddress.length;
        }
        
        return payload;
    }
}
