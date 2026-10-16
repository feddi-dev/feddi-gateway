package dev.feddi.federation.engine.parser;

import dev.feddi.federation.engine.parser.FieldSelectionMap.PathSegment;
import graphql.language.Argument;
import graphql.language.EnumValue;
import graphql.language.IntValue;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PathSegmentTest {

    private static final Argument IMPERIAL = new Argument("unit", new EnumValue("IMPERIAL"));
    private static final Argument METRIC = new Argument("unit", new EnumValue("METRIC"));

    @Test
    void segmentsWithEqualArgumentTextAreEqual() {
        PathSegment segment = new PathSegment("weight", null, List.of(IMPERIAL));
        PathSegment same = new PathSegment("weight", null, List.of(new Argument("unit", new EnumValue("IMPERIAL"))));

        assertThat(segment).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(segment.printedArguments()).isEqualTo("unit: IMPERIAL");
    }

    @Test
    void segmentsDifferInFieldTypeConditionOrArguments() {
        PathSegment segment = new PathSegment("weight", null, List.of(IMPERIAL));

        assertThat(segment).isNotEqualTo(new PathSegment("weight", null, List.of(METRIC)))
            .isNotEqualTo(new PathSegment("weight", null, List.of(IMPERIAL, new Argument("n", new IntValue(BigInteger.ONE)))))
            .isNotEqualTo(new PathSegment("weight", "Product", List.of(IMPERIAL)))
            .isNotEqualTo(new PathSegment("height", null, List.of(IMPERIAL)))
            .isNotEqualTo("weight");
    }

    @Test
    void segmentWithoutArgumentsPrintsNone() {
        PathSegment segment = new PathSegment("weight", null, null);

        assertThat(segment.hasArguments()).isFalse();
        assertThat(segment.printedArguments()).isEmpty();
        assertThat(segment).isEqualTo(new PathSegment("weight"));
    }
}
