package dev.feddi.federation.app;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FeddiGatewayApplicationTest {

    @Test
    void usesTwoEventLoopsPerCoreAndAtLeastFour() {
        assertThat(FeddiGatewayApplication.ioWorkerCount(null, 1)).isEqualTo("4");
        assertThat(FeddiGatewayApplication.ioWorkerCount(null, 2)).isEqualTo("4");
        assertThat(FeddiGatewayApplication.ioWorkerCount(null, 4)).isEqualTo("8");
        assertThat(FeddiGatewayApplication.ioWorkerCount(null, 16)).isEqualTo("32");
    }

    @Test
    void keepsAConfiguredNumberOfEventLoops() {
        assertThat(FeddiGatewayApplication.ioWorkerCount("3", 16)).isEqualTo("3");
    }
}
