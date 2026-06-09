package com.maluca;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import reactor.core.publisher.Hooks;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MalucaProxyApplication {

    public static void main(String[] args) {
        // propagate trace context across reactive operator boundaries so
        // sub-spans and MDC trace ids survive thread hops
        Hooks.enableAutomaticContextPropagation();
        SpringApplication.run(MalucaProxyApplication.class, args);
    }
}
