package com.openim.server.config;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
public class ProductionProfile {
    private final Environment environment;

    public ProductionProfile(Environment environment) {
        this.environment = environment;
    }

    public boolean active() {
        return environment.acceptsProfiles(Profiles.of("prod"));
    }
}
