package com.maluca.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/** Matches IPs against a list of addresses/CIDR blocks (v4 and v6). */
public final class CidrSet {

    public static final CidrSet EMPTY = new CidrSet(List.of());

    private record Cidr(byte[] network, int prefixBits) {
        boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            int remainder = prefixBits % 8;
            if (remainder == 0 || fullBytes >= network.length) {
                return true;
            }
            int mask = 0xFF << (8 - remainder);
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }

    private final List<Cidr> cidrs;

    private CidrSet(List<Cidr> cidrs) {
        this.cidrs = cidrs;
    }

    public static CidrSet of(List<String> specs) {
        if (specs == null || specs.isEmpty()) {
            return EMPTY;
        }
        List<Cidr> parsed = new ArrayList<>(specs.size());
        for (String spec : specs) {
            try {
                String[] parts = spec.trim().split("/");
                byte[] network = InetAddress.getByName(parts[0]).getAddress();
                int bits = parts.length > 1 ? Integer.parseInt(parts[1]) : network.length * 8;
                parsed.add(new Cidr(network, bits));
            } catch (UnknownHostException | NumberFormatException e) {
                throw new IllegalArgumentException("Bad CIDR: " + spec, e);
            }
        }
        return new CidrSet(parsed);
    }

    public boolean contains(String ip) {
        if (cidrs.isEmpty()) {
            return false;
        }
        try {
            byte[] address = InetAddress.getByName(ip).getAddress();
            return cidrs.stream().anyMatch(c -> c.contains(address));
        } catch (UnknownHostException e) {
            return false;
        }
    }

    public boolean isEmpty() {
        return cidrs.isEmpty();
    }
}
