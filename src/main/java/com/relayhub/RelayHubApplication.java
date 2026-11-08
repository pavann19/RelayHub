package com.relayhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RelayHubApplication {

    public static void main(String[] args) {
        if (java.util.Arrays.asList(args).contains("--migrate-only")) {
            String[] forwarded = java.util.stream.Stream.concat(
                    java.util.Arrays.stream(args).filter(a -> !a.equals("--migrate-only")),
                    java.util.stream.Stream.of("--relayhub.workers.enabled=false")).toArray(String[]::new);
            try (var context = new org.springframework.boot.builder.SpringApplicationBuilder(RelayHubApplication.class)
                    .web(org.springframework.boot.WebApplicationType.NONE)
                    .properties("relayhub.workers.enabled=false").run(forwarded)) {
                context.getBean(org.flywaydb.core.Flyway.class).validate();
            }
            return;
        }
        SpringApplication.run(RelayHubApplication.class, args);
    }

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
