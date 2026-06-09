package com.maluca.identity;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.maluca.config.MalucaProperties;

/**
 * Flags requests originating from datacenter/cloud IP space. Real users
 * browse from residential/mobile networks; traffic from EC2 or DigitalOcean
 * is a meaningful (never decisive) bot signal, and bumps PoW difficulty.
 *
 * Backed by a configured CIDR list. The production version of this is a
 * MaxMind GeoLite2-ASN lookup refreshed weekly; the interface is the same.
 */
@Component
public class DatacenterDetector {

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

    private final List<Cidr> cidrs = new ArrayList<>();

    public DatacenterDetector(MalucaProperties properties) {
        for (String spec : properties.identity().datacenterCidrs()) {
            try {
                String[] parts = spec.trim().split("/");
                byte[] network = InetAddress.getByName(parts[0]).getAddress();
                int bits = parts.length > 1 ? Integer.parseInt(parts[1]) : network.length * 8;
                cidrs.add(new Cidr(network, bits));
            } catch (UnknownHostException | NumberFormatException | ArrayIndexOutOfBoundsException e) {
                throw new IllegalArgumentException("Bad datacenter CIDR: " + spec, e);
            }
        }
    }

    public boolean isDatacenter(String ip) {
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
}
