package dev.feddi.federation.engine.planner;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionPlanReuseSourcesTest {

    @Test
    void planWithoutReuseSourcesHasNone() {
        assertThat(new ExecutionPlan(List.of(), null).reuseSources()).isEmpty();
        assertThat(new ExecutionPlan(List.of()).reuseSources()).isEmpty();
    }

    @Test
    void reuseSourceKeysAreInterned() {
        var source = new ExecutionPlan.ReuseSource(1, List.of(new String("products")),
            Map.of(new String("name"), new String("title")));

        assertThat(source.path().get(0)).isSameAs("products");
        assertThat(source.fields().keySet().iterator().next()).isSameAs("name");
        assertThat(source.fields().values().iterator().next()).isSameAs("title");
    }
}
