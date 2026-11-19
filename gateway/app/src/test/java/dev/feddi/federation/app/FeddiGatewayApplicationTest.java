package dev.feddi.federation.app;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FeddiGatewayApplicationTest {

    @Test
    void usesTwoEventLoopsPerCoreAndAtLeastFour() {
        assertThat(FeddiGatewayApplication.ioWorkerCount(1)).isEqualTo(4);
        assertThat(FeddiGatewayApplication.ioWorkerCount(2)).isEqualTo(4);
        assertThat(FeddiGatewayApplication.ioWorkerCount(4)).isEqualTo(8);
        assertThat(FeddiGatewayApplication.ioWorkerCount(16)).isEqualTo(32);
    }
}
