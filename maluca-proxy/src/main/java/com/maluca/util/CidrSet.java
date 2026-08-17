package com.maluca.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/** Matches IPs against a list of addresses/CIDR blocks (v4 and v6). */
public final class CidrSet {

    public static final CidrSet EMPTY = new CidrSet(List.of(), List.of());

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
    private final List<String> specs;

    private CidrSet(List<Cidr> cidrs, List<String> specs) {
        this.cidrs = cidrs;
        this.specs = specs;
    }

    public static CidrSet of(List<String> specs) {
        if (specs == null || specs.isEmpty()) {
            return EMPTY;
        }
        List<Cidr> parsed = new ArrayList<>(specs.size());
        List<String> normalized = new ArrayList<>(specs.size());
        for (String spec : specs) {
            try {
                if (spec == null) {
                    throw new IllegalArgumentException("Bad CIDR: null");
                }
                String normalizedSpec = spec.trim();
                String[] parts = normalizedSpec.split("/", -1);
                if (parts.length < 1 || parts.length > 2 || !isLiteralIp(parts[0])) {
                    throw new IllegalArgumentException("Bad CIDR: " + spec);
                }
                byte[] network = InetAddress.getByName(parts[0]).getAddress();
                int bits = parts.length > 1 ? Integer.parseInt(parts[1]) : network.length * 8;
                if (bits < 0 || bits > network.length * 8) {
                    throw new IllegalArgumentException("Bad CIDR prefix: " + spec);
                }
                parsed.add(new Cidr(network, bits));
                normalized.add(normalizedSpec);
            } catch (UnknownHostException | NumberFormatException e) {
                throw new IllegalArgumentException("Bad CIDR: " + spec, e);
            }
        }
        return new CidrSet(List.copyOf(parsed), List.copyOf(normalized));
    }

    public boolean contains(String ip) {
        if (cidrs.isEmpty()) {
            return false;
        }
        if (!isLiteralIp(ip)) {
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

    /** Original normalized CIDR strings, suitable for guarded admin output. */
    public List<String> specs() {
        return specs;
    }

    private static boolean isLiteralIp(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (value.contains(":")) {
            return value.matches("[0-9A-Fa-f:]+");
        }
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (!octet.matches("[0-9]{1,3}") || Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return true;
    }
}
