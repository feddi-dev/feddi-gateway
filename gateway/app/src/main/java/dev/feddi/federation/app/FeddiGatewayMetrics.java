package dev.feddi.federation.app;

import dev.feddi.federation.engine.executor.ExecutionListener;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    // Timers by tag value: registering a meter on every record builds its configuration and looks it up
    private final Map<String, Timer> requestDurations = new ConcurrentHashMap<>();
    private final Map<String, Timer> subgraphDurations = new ConcurrentHashMap<>();

    public FeddiGatewayMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.requestDuration = Timer.builder("feddi.gateway.request.duration")
                .description("Total GraphQL request duration")
                .publishPercentileHistogram()
                .register(registry);

        this.requestErrors = Counter.builder("feddi.gateway.request.errors")
                .description("GraphQL request errors")
                .register(registry);

        this.planningDuration = Timer.builder("feddi.gateway.planning.duration")
                .description("Query planning duration")
                .publishPercentileHistogram()
                .register(registry);
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void recordRequestDuration(Timer.Sample sample, String operationType) {
        sample.stop(requestDurations.computeIfAbsent(operationType, type ->
            Timer.builder("feddi.gateway.request.duration")
                .tag("operation.type", type)
                .publishPercentileHistogram()
                .register(registry)));
    }

    public void recordRequestError() {
        requestErrors.increment();
    }

    public void recordPlanningDuration(Timer.Sample sample) {
        sample.stop(planningDuration);
    }

    @Override
    public void onSubgraphFetchComplete(String subgraphName, long durationNanos, boolean success) {
        subgraphDurations.computeIfAbsent(subgraphName, name ->
            Timer.builder("feddi.gateway.subgraph.duration")
                .tag("subgraph.name", name)
                .publishPercentileHistogram()
                .register(registry))
            .record(durationNanos, TimeUnit.NANOSECONDS);

        if (!success) {
            Counter.builder("feddi.gateway.subgraph.errors")
                    .tag("subgraph.name", subgraphName)
                    .register(registry)
                    .increment();
        }
    }

    @Override
    public void onSubgraphTimeout(String subgraphName) {
        Counter.builder("feddi.gateway.subgraph.timeouts")
                .tag("subgraph.name", subgraphName)
                .register(registry)
                .increment();
    }
}
