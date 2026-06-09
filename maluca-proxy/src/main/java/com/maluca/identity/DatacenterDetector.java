package com.maluca.identity;

import org.springframework.stereotype.Component;

import com.maluca.config.MalucaProperties;
import com.maluca.util.CidrSet;

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

    private final CidrSet cidrs;

    public DatacenterDetector(MalucaProperties properties) {
        this.cidrs = CidrSet.of(properties.identity().datacenterCidrs());
    }

    public boolean isDatacenter(String ip) {
        return cidrs.contains(ip);
    }
}
