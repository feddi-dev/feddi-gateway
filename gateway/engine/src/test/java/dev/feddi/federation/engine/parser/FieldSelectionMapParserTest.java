package dev.feddi.federation.engine.parser;

import dev.feddi.federation.engine.parser.FieldSelectionMap.Alternative;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ListSelection;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ObjectField;
import dev.feddi.federation.engine.parser.FieldSelectionMap.ObjectSelection;
import dev.feddi.federation.engine.parser.FieldSelectionMap.Path;
import dev.feddi.federation.engine.parser.FieldSelectionMap.PathSegment;
import dev.feddi.federation.engine.parser.FieldSelectionMap.SelectedValue;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FieldSelectionMapParserTest {

    @Test
    void simpleParse() {
        SelectedValue selectedValue = FieldSelectionMapParser.parseFieldSelectionMap("foo");

        SelectedValue expected = new SelectedValue(
            new Path(
                new PathSegment("foo")
            )
        );
        assertThat(selectedValue).isEqualTo(expected);
    }

    @Test
    void dotSeparatedPath() {
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap("foo.bar.baz");

        SelectedValue expected = new SelectedValue(
            new Path(
                new PathSegment("foo"),
                new PathSegment("bar"),
                new PathSegment("baz")
            )
        );
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void dotSeparatedPathWithTypeCondition() {
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap("foo.bar<Bar>.baz");

        SelectedValue expected = new SelectedValue(
            new Path(
                new PathSegment("foo"),
                new PathSegment("bar", "Bar"),
                new PathSegment("baz")
            )
        );
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void pathWithInitialTypeCondition() {
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap("<TypeA>.foo.bar");

        // Initial type condition is stored separately on Path
        SelectedValue expected = new SelectedValue(
            new Path("TypeA",
                new PathSegment("foo"),
                new PathSegment("bar")
            )
        );
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void pathWithInitialAndInfixTypeConditions() {
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap("<TypeA>.foo<TypeB>.bar");

        // Initial type condition on Path, infix type condition on PathSegment
        SelectedValue expected = new SelectedValue(
            new Path("TypeA",
                new PathSegment("foo", "TypeB"),
                new PathSegment("bar")
            )
        );
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void infixTypeCondition_verifyAstStructure() {
        // Infix type condition: field<Type>.next - type condition is on the segment
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap("media<Movie>.title");

        assertThat(actual.alternatives()).hasSize(1);
        Path path = (Path) actual.alternatives().get(0);

        // No initial type condition
        assertThat(path.hasInitialTypeCondition()).isFalse();
        assertThat(path.initialTypeCondition()).isNull();

        // Two segments: media<Movie> and title
        assertThat(path.segments()).hasSize(2);

        // First segment has infix type condition
        PathSegment firstSegment = path.segments().get(0);
        assertThat(firstSegment.fieldName()).isEqualTo("media");
        assertThat(firstSegment.hasTypeCondition()).isTrue();
        assertThat(firstSegment.typeCondition()).isEqualTo("Movie");

        // Second segment has no type condition
        PathSegment secondSegment = path.segments().get(1);
        assertThat(secondSegment.fieldName()).isEqualTo("title");
        assertThat(secondSegment.hasTypeCondition()).isFalse();
    }

    @Test
    void initialTypeCondition_verifyAstStructure() {
        // Initial type condition: <Type>.field - type condition is on the Path
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap("<Movie>.title");

        assertThat(actual.alternatives()).hasSize(1);
        Path path = (Path) actual.alternatives().get(0);

        // Has initial type condition
        assertThat(path.hasInitialTypeCondition()).isTrue();
        assertThat(path.initialTypeCondition()).isEqualTo("Movie");

        // Single segment with no type condition
        assertThat(path.segments()).hasSize(1);
        PathSegment segment = path.segments().get(0);
        assertThat(segment.fieldName()).isEqualTo("title");
        assertThat(segment.hasTypeCondition()).isFalse();
    }

    @Test
    void chainedInfixTypeConditions() {
        // Multiple infix type conditions: content<Article>.author<Person>.name
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap("content<Article>.author<Person>.name");

        assertThat(actual.alternatives()).hasSize(1);
        Path path = (Path) actual.alternatives().get(0);

        assertThat(path.hasInitialTypeCondition()).isFalse();
        assertThat(path.segments()).hasSize(3);

        // content<Article>
        assertThat(path.segments().get(0).fieldName()).isEqualTo("content");
        assertThat(path.segments().get(0).typeCondition()).isEqualTo("Article");

        // author<Person>
        assertThat(path.segments().get(1).fieldName()).isEqualTo("author");
        assertThat(path.segments().get(1).typeCondition()).isEqualTo("Person");

        // name (no type condition)
        assertThat(path.segments().get(2).fieldName()).isEqualTo("name");
        assertThat(path.segments().get(2).hasTypeCondition()).isFalse();
    }

    @Test
    void initialPlusChainedInfixTypeConditions() {
        // Initial + chained infix: <Content>.article<NewsArticle>.headline
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap("<Content>.article<NewsArticle>.headline");

        assertThat(actual.alternatives()).hasSize(1);
        Path path = (Path) actual.alternatives().get(0);

        // Initial type condition
        assertThat(path.hasInitialTypeCondition()).isTrue();
        assertThat(path.initialTypeCondition()).isEqualTo("Content");

        assertThat(path.segments()).hasSize(2);

        // article<NewsArticle>
        assertThat(path.segments().get(0).fieldName()).isEqualTo("article");
        assertThat(path.segments().get(0).typeCondition()).isEqualTo("NewsArticle");

        // headline (no type condition)
        assertThat(path.segments().get(1).fieldName()).isEqualTo("headline");
        assertThat(path.segments().get(1).hasTypeCondition()).isFalse();
    }

    @Test
    void standaloneObjectShorthand() {
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap("{ foo bar }");

        SelectedValue expected = new SelectedValue(
            new ObjectSelection(
                new ObjectField("foo", new SelectedValue(Path.of("foo"))),
                new ObjectField("bar", new SelectedValue(Path.of("bar")))
            )
        );
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void standaloneObjectExplicit() {
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap("{ foo: path.to.foo bar : baz }");

        SelectedValue expected = new SelectedValue(
            new ObjectSelection(
                new ObjectField("foo", new SelectedValue(
                    new Path(
                        new PathSegment("path"),
                        new PathSegment("to"),
                        new PathSegment("foo")
                    )
                )),
                new ObjectField("bar", new SelectedValue(
                    Path.of("baz")
                ))
            )
        );
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void pathPrefixedObjectShorthand() {
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap("dimensions.{ width height }");

        SelectedValue expected = new SelectedValue(
            new ObjectSelection(
                Path.of("dimensions"),
                new ObjectField("width", new SelectedValue(Path.of("width"))),
                new ObjectField("height", new SelectedValue(Path.of("height")))
            )
        );
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void pathPrefixedObjectExplicit() {
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap("user.address.{ street: fullStreet city }");

        SelectedValue expected = new SelectedValue(
            new ObjectSelection(
                new Path(
                    new PathSegment("user"),
                    new PathSegment("address")
                ),
                new ObjectField("street", new SelectedValue(Path.of("fullStreet"))),
                new ObjectField("city", new SelectedValue(Path.of("city")))
            )
        );
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void pathPrefixedListWithPathElement() {
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap("items[part.id]");

        SelectedValue expected = new SelectedValue(
            new ListSelection(
                Path.of("items"),
                new SelectedValue(
                    new Path(
                        new PathSegment("part"),
                        new PathSegment("id")
                    )
                )
            )
        );
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void pathPrefixedListWithObjectShorthandElement() {
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap("parts[{ id name }]");

        SelectedValue expected = new SelectedValue(
            new ListSelection(
                Path.of("parts"),
                new SelectedValue(
                    new ObjectSelection(
                        new ObjectField("id", new SelectedValue(Path.of("id"))),
                        new ObjectField("name", new SelectedValue(Path.of("name")))
                    )
                )
            )
        );
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void pathPrefixedListWithObjectExplicitElement() {
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap("products[{ sku: item.code name: item.description }]");

        SelectedValue expected = new SelectedValue(
            new ListSelection(
                Path.of("products"),
                new SelectedValue(
                    new ObjectSelection(
                        new ObjectField("sku", new SelectedValue(
                            new Path(
                                new PathSegment("item"),
                                new PathSegment("code")
                            )
                        )),
                        new ObjectField("name", new SelectedValue(
                            new Path(
                                new PathSegment("item"),
                                new PathSegment("description")
                            )
                        ))
                    )
                )
            ));
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void alternativesSimplePaths() {
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap("book.title | movie.name");

        SelectedValue expected = new SelectedValue(
            new Path(
                new PathSegment("book"),
                new PathSegment("title")
            ),
            new Path(
                new PathSegment("movie"),
                new PathSegment("name")
            )
        );
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void alternativesWithLeadingPipe() {
        // Spec allows optional leading pipe: `|? SelectedValueEntry`
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap("| book.title | movie.name");

        SelectedValue expected = new SelectedValue(
            new Path(
                new PathSegment("book"),
                new PathSegment("title")
            ),
            new Path(
                new PathSegment("movie"),
                new PathSegment("name")
            )
        );
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void singleValueWithLeadingPipe() {
        // Spec allows optional leading pipe even for single value
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap("| foo");

        SelectedValue expected = new SelectedValue(Path.of("foo"));
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void alternativesWithObjects() {
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap("{ bookId : <Book>.id } | { movieId : <Movie>.id }");

        // Initial type conditions stored separately on Path
        SelectedValue expected = new SelectedValue(
            new ObjectSelection(
                new ObjectField("bookId", new SelectedValue(
                    new Path("Book", new PathSegment("id"))
                ))
            ),
            new ObjectSelection(
                new ObjectField("movieId", new SelectedValue(
                    new Path("Movie", new PathSegment("id"))
                ))
            )
        );
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void alternativesMixedTypes() {
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap("author.name | bookDetails.{ title isbn } | ids[bookId]");

        SelectedValue expected = new SelectedValue(
            new Path(
                new PathSegment("author"),
                new PathSegment("name")
            ),
            new ObjectSelection(
                Path.of("bookDetails"),
                new ObjectField("title", new SelectedValue(Path.of("title"))),
                new ObjectField("isbn", new SelectedValue(Path.of("isbn")))
            ),
            new ListSelection(
                Path.of("ids"),
                new SelectedValue(
                    Path.of("bookId"))
            )
        );
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void complexNestedObject() {
        String input = "{ package: { weight: dimensions.weight, size: dimensions.{ width height area: surface.area } } }";
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap(input);

        ObjectField sizeField = new ObjectField("size",
            new SelectedValue(
                new ObjectSelection(
                    Path.of("dimensions"),
                    new ObjectField("width", new SelectedValue(Path.of("width"))),
                    new ObjectField("height", new SelectedValue(Path.of("height"))),
                    new ObjectField("area", new SelectedValue(
                        new Path(
                            new PathSegment("surface"),
                            new PathSegment("area")
                        )
                    ))
                )
            )
        );

        ObjectField packageField = new ObjectField("package",
            new SelectedValue(
                new ObjectSelection(
                    new ObjectField("weight", new SelectedValue(
                        new Path(
                            new PathSegment("dimensions"),
                            new PathSegment("weight")
                        )
                    )),
                    sizeField
                )
            )
        );

        assertThat(actual).isEqualTo(new SelectedValue(new ObjectSelection(packageField)));
    }

    @Test
    void deeplyNestedListAndObjects() {
        String input = "orders[customer.addresses[{ street city } | location.coords]]";
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap(input);

        SelectedValue addressElementValue = new SelectedValue(
            new ObjectSelection(
                new ObjectField("street", new SelectedValue(Path.of("street"))),
                new ObjectField("city", new SelectedValue(Path.of("city")))
            ),
            new Path(
                new PathSegment("location"),
                new PathSegment("coords")
            )
        );

        SelectedValue customerAddressesElementValue = new SelectedValue(
            new ListSelection(
                new Path(
                    new PathSegment("customer"),
                    new PathSegment("addresses")
                ),
                addressElementValue
            )
        );

        SelectedValue expected = new SelectedValue(
            new ListSelection(
                Path.of("orders"),
                customerAddressesElementValue
            )
        );
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void pathPrefixedListWithNestedListElement() {
        String input = "parts[[{ id name }]]";
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap(input);

        SelectedValue expected = new SelectedValue(
            new ListSelection(
                Path.of("parts"),
                new SelectedValue(
                    new ListSelection(
                        new SelectedValue(
                            new ObjectSelection(
                                new ObjectField("id", new SelectedValue(Path.of("id"))),
                                new ObjectField("name", new SelectedValue(Path.of("name")))
                            )
                        )
                    )
                )
            )
        );

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void nestedListsAndObjectsAsAlternatives() {
        String input = "data[{ typeA: item<A>.value } | { typeB: item<B>.value[subItem<Sub>.name] }]";
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap(input);

        Alternative listElementAlt1 = new ObjectSelection(
            new ObjectField("typeA", new SelectedValue(
                new Path(
                    new PathSegment("item", "A"),
                    new PathSegment("value")
                )
            )
            )
        );

        Alternative listElementAlt2 = new ObjectSelection(
            new ObjectField("typeB",
                new SelectedValue(
                    new ListSelection(
                        new Path(
                            new PathSegment("item", "B"),
                            new PathSegment("value")
                        ),
                        new SelectedValue(
                            new Path(
                                new PathSegment("subItem", "Sub"),
                                new PathSegment("name")
                            )
                        )
                    )
                )
            )
        );

        SelectedValue expected = new SelectedValue(
            new ListSelection(
                Path.of("data"),
                new SelectedValue(
                    listElementAlt1,
                    listElementAlt2
                )
            )
        );

        assertThat(actual).isEqualTo(expected);
    }

    // ====== Additional tests for spec compliance ======

    @Test
    void specExample_bookTitle() {
        // From spec: book.title
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap("book.title");
        
        SelectedValue expected = new SelectedValue(
            new Path(
                new PathSegment("book"),
                new PathSegment("title")
            )
        );
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void specExample_mediaByIdWithTypeCondition() {
        // From spec: mediaById<Book>.isbn
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap("mediaById<Book>.isbn");
        
        SelectedValue expected = new SelectedValue(
            new Path(
                new PathSegment("mediaById", "Book"),
                new PathSegment("isbn")
            )
        );
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void specExample_partsWithId() {
        // From spec: parts[id]
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap("parts[id]");
        
        SelectedValue expected = new SelectedValue(
            new ListSelection(
                Path.of("parts"),
                new SelectedValue(Path.of("id"))
            )
        );
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void specExample_dimensionObjectSelection() {
        // From spec: dimension.{ size, weight }
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap("dimension.{ size, weight }");
        
        SelectedValue expected = new SelectedValue(
            new ObjectSelection(
                Path.of("dimension"),
                new ObjectField("size", new SelectedValue(Path.of("size"))),
                new ObjectField("weight", new SelectedValue(Path.of("weight")))
            )
        );
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void specExample_coordinatesWithRenamedFields() {
        // From spec: { coordinates: coordinates[{ lat: x, lon: y }] }
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap("{ coordinates: coordinates[{ lat: x, lon: y }] }");
        
        SelectedValue expected = new SelectedValue(
            new ObjectSelection(
                new ObjectField("coordinates", new SelectedValue(
                    new ListSelection(
                        Path.of("coordinates"),
                        new SelectedValue(
                            new ObjectSelection(
                                new ObjectField("lat", new SelectedValue(Path.of("x"))),
                                new ObjectField("lon", new SelectedValue(Path.of("y")))
                            )
                        )
                    )
                ))
            )
        );
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void specExample_alternativesWithTypeConditions() {
        // From spec: mediaById<Book>.title | mediaById<Movie>.movieTitle
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap("mediaById<Book>.title | mediaById<Movie>.movieTitle");
        
        SelectedValue expected = new SelectedValue(
            new Path(
                new PathSegment("mediaById", "Book"),
                new PathSegment("title")
            ),
            new Path(
                new PathSegment("mediaById", "Movie"),
                new PathSegment("movieTitle")
            )
        );
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void specExample_objectAlternativesWithTypeConditions() {
        // From spec: { movieId: <Movie>.id } | { productId: <Product>.id }
        // Initial type conditions stored separately on Path
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap("{ movieId: <Movie>.id } | { productId: <Product>.id }");

        SelectedValue expected = new SelectedValue(
            new ObjectSelection(
                new ObjectField("movieId", new SelectedValue(
                    new Path("Movie", new PathSegment("id"))
                ))
            ),
            new ObjectSelection(
                new ObjectField("productId", new SelectedValue(
                    new Path("Product", new PathSegment("id"))
                ))
            )
        );
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void specExample_nestedAlternativesInsideObject() {
        // From spec: { nested: { bookId: <Book>.id } | { movieId: <Movie>.id } }
        // Initial type conditions stored separately on Path
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap(
            "{ nested: { bookId: <Book>.id } | { movieId: <Movie>.id } }");

        SelectedValue expected = new SelectedValue(
            new ObjectSelection(
                new ObjectField("nested", new SelectedValue(
                    new ObjectSelection(
                        new ObjectField("bookId", new SelectedValue(
                            new Path("Book", new PathSegment("id"))
                        ))
                    ),
                    new ObjectSelection(
                        new ObjectField("movieId", new SelectedValue(
                            new Path("Movie", new PathSegment("id"))
                        ))
                    )
                ))
            )
        );
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void specExample_packageWithNestedDimension() {
        // From spec: { weight, dimension: dimension.{ width, height } }
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap(
            "{ weight, dimension: dimension.{ width, height } }");

        SelectedValue expected = new SelectedValue(
            new ObjectSelection(
                new ObjectField("weight", new SelectedValue(Path.of("weight"))),
                new ObjectField("dimension", new SelectedValue(
                    new ObjectSelection(
                        Path.of("dimension"),
                        new ObjectField("width", new SelectedValue(Path.of("width"))),
                        new ObjectField("height", new SelectedValue(Path.of("height")))
                    )
                ))
            )
        );
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void specExample_renamedNestedDimension() {
        // From spec: { weight, size: dimension.{ width, height } }
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap(
            "{ weight, size: dimension.{ width, height } }");

        SelectedValue expected = new SelectedValue(
            new ObjectSelection(
                new ObjectField("weight", new SelectedValue(Path.of("weight"))),
                new ObjectField("size", new SelectedValue(
                    new ObjectSelection(
                        Path.of("dimension"),
                        new ObjectField("width", new SelectedValue(Path.of("width"))),
                        new ObjectField("height", new SelectedValue(Path.of("height")))
                    )
                ))
            )
        );
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void specExample_listWithObjectSelection() {
        // From spec: dimensions[{ width, height }]
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap("dimensions[{ width, height }]");

        SelectedValue expected = new SelectedValue(
            new ListSelection(
                Path.of("dimensions"),
                new SelectedValue(
                    new ObjectSelection(
                        new ObjectField("width", new SelectedValue(Path.of("width"))),
                        new ObjectField("height", new SelectedValue(Path.of("height")))
                    )
                )
            )
        );
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void specExample_nestedListWithObjectInput() {
        // From spec: parts[[{ id, name }]] for [[Part!]]! type
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap("parts[[{ id, name }]]");

        SelectedValue expected = new SelectedValue(
            new ListSelection(
                Path.of("parts"),
                new SelectedValue(
                    new ListSelection(
                        new SelectedValue(
                            new ObjectSelection(
                                new ObjectField("id", new SelectedValue(Path.of("id"))),
                                new ObjectField("name", new SelectedValue(Path.of("name")))
                            )
                        )
                    )
                )
            )
        );
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void whitespaceHandling_noSpaces() {
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap("{foo:bar}");
        
        SelectedValue expected = new SelectedValue(
            new ObjectSelection(
                new ObjectField("foo", new SelectedValue(Path.of("bar")))
            )
        );
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void whitespaceHandling_extraSpaces() {
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap("{  foo  :  bar  }");
        
        SelectedValue expected = new SelectedValue(
            new ObjectSelection(
                new ObjectField("foo", new SelectedValue(Path.of("bar")))
            )
        );
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void whitespaceHandling_newlinesAndTabs() {
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap("{\n\tfoo:\n\t\tbar\n}");
        
        SelectedValue expected = new SelectedValue(
            new ObjectSelection(
                new ObjectField("foo", new SelectedValue(Path.of("bar")))
            )
        );
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void fieldNamesWithUnderscores() {
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap("_private_field.nested_value");
        
        SelectedValue expected = new SelectedValue(
            new Path(
                new PathSegment("_private_field"),
                new PathSegment("nested_value")
            )
        );
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void fieldNamesWithNumbers() {
        SelectedValue actual = FieldSelectionMapParser.parseFieldSelectionMap("field1.field2");
        
        SelectedValue expected = new SelectedValue(
            new Path(
                new PathSegment("field1"),
                new PathSegment("field2")
            )
        );
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void invalidSyntax_throwsException() {
        assertThatThrownBy(() -> FieldSelectionMapParser.parseFieldSelectionMap("{ foo: }"))
            .isInstanceOf(InvalidSyntaxException.class);
    }

    @Test
    void invalidSyntax_unclosedBrace_throwsException() {
        assertThatThrownBy(() -> FieldSelectionMapParser.parseFieldSelectionMap("{ foo: bar"))
            .isInstanceOf(InvalidSyntaxException.class);
    }

    @Test
    void invalidSyntax_unclosedBracket_throwsException() {
        assertThatThrownBy(() -> FieldSelectionMapParser.parseFieldSelectionMap("items[foo"))
            .isInstanceOf(InvalidSyntaxException.class);
    }

    // ====== Printer Tests ======

    @Test
    void printer_simplePath() {
        SelectedValue value = new SelectedValue(Path.of("foo"));
        String printed = FieldSelectionMapPrinter.print(value);
        assertThat(printed).isEqualTo("foo");
    }

    @Test
    void printer_dotSeparatedPath() {
        SelectedValue value = new SelectedValue(
            new Path(
                new PathSegment("foo"),
                new PathSegment("bar"),
                new PathSegment("baz")
            )
        );
        String printed = FieldSelectionMapPrinter.print(value);
        assertThat(printed).isEqualTo("foo.bar.baz");
    }

    @Test
    void printer_pathWithTypeCondition() {
        SelectedValue value = new SelectedValue(
            new Path(
                new PathSegment("mediaById", "Book"),
                new PathSegment("isbn")
            )
        );
        String printed = FieldSelectionMapPrinter.print(value);
        assertThat(printed).isEqualTo("mediaById<Book>.isbn");
    }

    @Test
    void printer_objectSelectionShorthand() {
        SelectedValue value = new SelectedValue(
            new ObjectSelection(
                new ObjectField("foo", new SelectedValue(Path.of("foo"))),
                new ObjectField("bar", new SelectedValue(Path.of("bar")))
            )
        );
        String printed = FieldSelectionMapPrinter.print(value);
        assertThat(printed).isEqualTo("{ foo bar }");
    }

    @Test
    void printer_objectSelectionExplicit() {
        SelectedValue value = new SelectedValue(
            new ObjectSelection(
                new ObjectField("key", new SelectedValue(Path.of("value")))
            )
        );
        String printed = FieldSelectionMapPrinter.print(value);
        assertThat(printed).isEqualTo("{ key: value }");
    }

    @Test
    void printer_objectSelectionWithPathPrefix() {
        SelectedValue value = new SelectedValue(
            new ObjectSelection(
                Path.of("dimensions"),
                new ObjectField("width", new SelectedValue(Path.of("width"))),
                new ObjectField("height", new SelectedValue(Path.of("height")))
            )
        );
        String printed = FieldSelectionMapPrinter.print(value);
        assertThat(printed).isEqualTo("dimensions.{ width height }");
    }

    @Test
    void printer_listSelection() {
        SelectedValue value = new SelectedValue(
            new ListSelection(
                Path.of("parts"),
                new SelectedValue(Path.of("id"))
            )
        );
        String printed = FieldSelectionMapPrinter.print(value);
        assertThat(printed).isEqualTo("parts[id]");
    }

    @Test
    void printer_alternatives() {
        SelectedValue value = new SelectedValue(
            new Path(
                new PathSegment("book"),
                new PathSegment("title")
            ),
            new Path(
                new PathSegment("movie"),
                new PathSegment("name")
            )
        );
        String printed = FieldSelectionMapPrinter.print(value);
        assertThat(printed).isEqualTo("book.title | movie.name");
    }

    @Test
    void printer_nestedListWithObject() {
        SelectedValue value = new SelectedValue(
            new ListSelection(
                Path.of("parts"),
                new SelectedValue(
                    new ObjectSelection(
                        new ObjectField("id", new SelectedValue(Path.of("id"))),
                        new ObjectField("name", new SelectedValue(Path.of("name")))
                    )
                )
            )
        );
        String printed = FieldSelectionMapPrinter.print(value);
        assertThat(printed).isEqualTo("parts[{ id name }]");
    }

    // ====== Round-trip Tests (parse -> print -> parse) ======

    @Test
    void roundTrip_simplePath() {
        assertRoundTrip("foo");
    }

    @Test
    void roundTrip_dotSeparatedPath() {
        assertRoundTrip("foo.bar.baz");
    }

    @Test
    void roundTrip_pathWithTypeCondition() {
        assertRoundTrip("mediaById<Book>.isbn");
    }

    @Test
    void roundTrip_objectShorthand() {
        assertRoundTrip("{ foo bar }");
    }

    @Test
    void roundTrip_objectExplicit() {
        assertRoundTrip("{ key: value }");
    }

    @Test
    void roundTrip_objectWithPathPrefix() {
        assertRoundTrip("dimensions.{ width height }");
    }

    @Test
    void roundTrip_listSelection() {
        assertRoundTrip("parts[id]");
    }

    @Test
    void roundTrip_listWithObject() {
        assertRoundTrip("parts[{ id name }]");
    }

    @Test
    void roundTrip_alternatives() {
        assertRoundTrip("book.title | movie.name");
    }

    @Test
    void roundTrip_alternativesWithTypeConditions() {
        assertRoundTrip("mediaById<Book>.title | mediaById<Movie>.movieTitle");
    }

    @Test
    void roundTrip_objectAlternativesWithTypeConditions() {
        assertRoundTrip("{ movieId: <Movie>.id } | { productId: <Product>.id }");
    }

    @Test
    void roundTrip_complexNested() {
        assertRoundTrip("orders[customer.addresses[{ street city } | location.coords]]");
    }

    @Test
    void roundTrip_nestedLists() {
        assertRoundTrip("parts[[{ id name }]]");
    }

    @Test
    void roundTrip_mixedAlternatives() {
        assertRoundTrip("author.name | bookDetails.{ title isbn } | ids[bookId]");
    }

    @Test
    void roundTrip_coordinatesExample() {
        assertRoundTrip("{ coordinates: coordinates[{ lat: x lon: y }] }");
    }

    @Test
    void roundTrip_complexTypeConditions() {
        assertRoundTrip("data[{ typeA: item<A>.value } | { typeB: item<B>.value[subItem<Sub>.name] }]");
    }

    @Test
    void roundTrip_nestedAlternativesInsideObject() {
        assertRoundTrip("{ nested: { bookId: <Book>.id } | { movieId: <Movie>.id } }");
    }

    @Test
    void roundTrip_packageWithNestedDimension() {
        assertRoundTrip("{ weight dimension: dimension.{ width height } }");
    }

    @Test
    void roundTrip_renamedNestedDimension() {
        assertRoundTrip("{ weight size: dimension.{ width height } }");
    }

    @Test
    void roundTrip_listWithObjectElement() {
        assertRoundTrip("dimensions[{ width height }]");
    }

    @Test
    void roundTrip_nestedListWithObjectInput() {
        assertRoundTrip("parts[[{ id name }]]");
    }

    /**
     * Helper method to verify that parsing, printing, and re-parsing yields the same AST.
     */
    private void assertRoundTrip(String input) {
        SelectedValue parsed = FieldSelectionMapParser.parseFieldSelectionMap(input);
        String printed = FieldSelectionMapPrinter.print(parsed);
        SelectedValue reparsed = FieldSelectionMapParser.parseFieldSelectionMap(printed);
        
        assertThat(reparsed)
            .as("Round-trip failed for input: '%s', printed as: '%s'", input, printed)
            .isEqualTo(parsed);
    }
}
