package dev.feddi.federation.app;

import dev.feddi.federation.engine.executor.ExecutionListener;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Gateway metrics backed by Micrometer. Exposes both pre-registered meters
 * and dynamic per-subgraph meters.
 */
@Component
public class FeddiGatewayMetrics implements ExecutionListener {

    private final MeterRegistry registry;
    private final Timer requestDuration;
    private final Counter requestErrors;
    private final Timer planningDuration;

    public FeddiGatewayMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.requestDuration = Timer.builder("gateway.request.duration")
                .description("Total GraphQL request duration")
                .publishPercentileHistogram()
                .register(registry);

        this.requestErrors = Counter.builder("gateway.request.errors")
                .description("GraphQL request errors")
                .register(registry);

        this.planningDuration = Timer.builder("gateway.planning.duration")
                .description("Query planning duration")
                .publishPercentileHistogram()
                .register(registry);
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void recordRequestDuration(Timer.Sample sample, String operationType) {
        sample.stop(Timer.builder("gateway.request.duration")
                .tag("operation.type", operationType)
                .publishPercentileHistogram()
                .register(registry));
    }

    public void recordRequestError() {
        requestErrors.increment();
    }

    public void recordPlanningDuration(Timer.Sample sample) {
        sample.stop(planningDuration);
    }

    @Override
    public void onSubgraphFetchComplete(String subgraphName, long durationNanos, boolean success) {
        Timer.builder("gateway.subgraph.duration")
                .tag("subgraph.name", subgraphName)
                .publishPercentileHistogram()
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);

        if (!success) {
            Counter.builder("gateway.subgraph.errors")
                    .tag("subgraph.name", subgraphName)
                    .register(registry)
                    .increment();
        }
    }

    @Override
    public void onSubgraphTimeout(String subgraphName) {
        Counter.builder("gateway.subgraph.timeouts")
                .tag("subgraph.name", subgraphName)
                .register(registry)
                .increment();
    }
}
