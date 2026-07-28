package com.openim.server.config;

import com.openim.server.auth.AuthenticationInterceptor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebMvcConfigTest {
    @Test
    void rejectsInsecureClientOriginsInProduction() {
        ProductionProfile productionProfile = mock(ProductionProfile.class);
        when(productionProfile.active()).thenReturn(true);
        assertThatThrownBy(() -> new WebMvcConfig(
                mock(AuthenticationInterceptor.class),
                "http://im.example.test",
                productionProfile
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must use HTTPS");
    }
}
