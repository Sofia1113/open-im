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

    /** 当前是否运行在生产环境(prod profile) */
    public boolean active() {
        return environment.acceptsProfiles(Profiles.of("prod"));
    }
}
