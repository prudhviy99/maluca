package com.maluca.identity;

import java.net.InetAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;

import com.maluca.state.ClientStateRepository;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Forward-confirmed reverse DNS (FCrDNS) for verified-crawler allowlisting.
 *
 * <p>Anyone can set {@code User-Agent: Googlebot}. What they cannot do is
 * make Google's DNS lie: the IP's PTR record must land in an official domain
 * (e.g. {@code crawl-66-249-66-1.googlebot.com}) AND that hostname must
 * resolve forward to the same IP. Both lookups must agree — a spoofer
 * controls neither zone.
 *
 * <p>DNS lookups are blocking, so they run on the bounded-elastic scheduler
 * and results are cached in Redis for 24h. On any DNS failure we return
 * "not verified" — the claimed bot is then scored as a spoofer, which is the
 * safe direction.
 */
@Service
public class VerifiedBotService {

    /** Official crawler PTR domains (suffix match on the FQDN). */
    private static final List<String> VERIFIED_SUFFIXES = List.of(
            ".googlebot.com", ".google.com",
            ".search.msn.com",                  // Bingbot
            ".applebot.apple.com",
            ".yandex.ru", ".yandex.net", ".yandex.com",
            ".duckduckgo.com");

    private static final Logger log = LoggerFactory.getLogger(VerifiedBotService.class);
    private static final Duration CACHE_TTL = Duration.ofHours(24);
    private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(2);

    /** Injectable for tests; production uses the JDK resolver. */
    public interface DnsResolver {
        String reverse(String ip) throws Exception;

        List<String> forward(String hostname) throws Exception;
    }

    static final DnsResolver JDK_RESOLVER = new DnsResolver() {
        @Override
        public String reverse(String ip) throws Exception {
            String name = InetAddress.getByName(ip).getCanonicalHostName();
            // getCanonicalHostName returns the IP itself when no PTR exists
            return name.equals(ip) ? null : name;
        }

        @Override
        public List<String> forward(String hostname) throws Exception {
            return Arrays.stream(InetAddress.getAllByName(hostname))
                    .map(InetAddress::getHostAddress)
                    .toList();
        }
    };

    private final ReactiveStringRedisTemplate redis;
    private final DnsResolver resolver;

    @org.springframework.beans.factory.annotation.Autowired
    public VerifiedBotService(ReactiveStringRedisTemplate redis) {
        this(redis, JDK_RESOLVER);
    }

    VerifiedBotService(ReactiveStringRedisTemplate redis, DnsResolver resolver) {
        this.redis = redis;
        this.resolver = resolver;
    }

    public Mono<Boolean> isVerifiedBot(String ip) {
        String cacheKey = ClientStateRepository.PREFIX + "fcrdns:" + ip;
        return redis.opsForValue().get(cacheKey)
                .map("1"::equals)
                .switchIfEmpty(Mono.defer(() -> verify(ip)
                        .flatMap(verified -> redis.opsForValue()
                                .set(cacheKey, verified ? "1" : "0", CACHE_TTL)
                                .thenReturn(verified))))
                .onErrorReturn(false);
    }

    Mono<Boolean> verify(String ip) {
        return Mono.fromCallable(() -> fcrdns(ip))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(LOOKUP_TIMEOUT)
                .onErrorReturn(false);
    }

    private boolean fcrdns(String ip) {
        try {
            String ptr = resolver.reverse(ip);
            if (ptr == null) {
                return false;
            }
            String normalized = ptr.toLowerCase(Locale.ROOT);
            boolean officialDomain = VERIFIED_SUFFIXES.stream().anyMatch(normalized::endsWith);
            if (!officialDomain) {
                return false;
            }
            // forward-confirm: the PTR hostname must resolve back to this IP
            return resolver.forward(ptr).contains(ip);
        } catch (Exception e) {
            log.debug("fcrdns_failed ip={} error={}", ip, e.toString());
            return false;
        }
    }
}
