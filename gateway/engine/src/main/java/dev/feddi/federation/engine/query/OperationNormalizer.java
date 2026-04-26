package dev.feddi.federation.engine.query;

import graphql.Directives;
import graphql.language.Argument;
import graphql.language.BooleanValue;
import graphql.language.Definition;
import graphql.language.Directive;
import graphql.language.Document;
import graphql.language.Field;
import graphql.language.FragmentDefinition;
import graphql.language.FragmentSpread;
import graphql.language.InlineFragment;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.language.TypeName;
import graphql.language.Value;
import graphql.language.VariableDefinition;
import graphql.schema.GraphQLCompositeType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLUnionType;

import dev.feddi.federation.engine.IntrospectionFields;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static graphql.schema.GraphQLTypeUtil.unwrapAll;

/**
 * Normalizes GraphQL operations in a single bottom-up traversal.
 *
 * <p>At each selection set level, the normalizer applies:
 * <ol>
 *   <li><b>Inline &amp; Filter</b>: Expands fragment spreads, evaluates @skip/@include directives</li>
 *   <li><b>Recurse</b>: Normalizes child selection sets (bottom-up)</li>
 *   <li><b>Simplify</b>: Removes redundant type conditions using schema info</li>
 *   <li><b>Merge</b>: Deduplicates fields and inline fragments</li>
 *   <li><b>Sort</b>: Orders selections deterministically</li>
 * </ol>
 *
 * <p>Each step can be individually enabled or disabled via the builder.
 * A {@link GraphQLSchema} is always required for type-aware normalization.
 */
public final class OperationNormalizer {

    private static final String SKIP_DIRECTIVE = Directives.SkipDirective.getName();
    private static final String INCLUDE_DIRECTIVE = Directives.IncludeDirective.getName();
    private static final String IF_ARGUMENT = "if";

    private final boolean inlineFragments;
    private final boolean processSkipInclude;
    private final boolean deduplicateFields;
    private final boolean sortSelections;
    private final GraphQLSchema schema;

    private OperationNormalizer(Builder builder) {
        this.inlineFragments = builder.inlineFragments;
        this.processSkipInclude = builder.processSkipInclude;
        this.deduplicateFields = builder.deduplicateFields;
        this.sortSelections = builder.sortSelections;
        this.schema = builder.schema;
    }

    /**
     * Creates a new builder with all normalization steps enabled by default.
     *
     * @param schema the GraphQL schema for type-aware normalization
     * @return a new Builder instance
     */
    public static Builder builder(GraphQLSchema schema) {
        return new Builder(schema);
    }

    /**
     * Normalizes a GraphQL document by applying the configured transformations.
     *
     * @param document the GraphQL document to normalize
     * @return a new normalized document
     * @throws CircularFragmentException if circular fragment references are detected
     * @throws FragmentNotFoundException if a referenced fragment is not defined
     * @throws FieldConflictException if fields with the same key have different names
     * @throws ArgumentConflictException if fields with the same key have different arguments
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Document normalize(Document document) {
        if (document == null) {
            throw new IllegalArgumentException("Document cannot be null");
        }

        Map<String, FragmentDefinition> fragments = collectFragments(document);
        List<Definition> newDefinitions = new ArrayList<>();

        for (var definition : document.getDefinitions()) {
            if (definition instanceof OperationDefinition operationDef) {
                newDefinitions.add(normalizeOperation(operationDef, fragments));
            } else if (definition instanceof FragmentDefinition) {
                if (!inlineFragments) {
                    newDefinitions.add(definition);
                }
            } else {
                newDefinitions.add(definition);
            }
        }

        return document.transform(builder -> builder.definitions(newDefinitions));
    }

    /**
     * Normalizes a specific operation within a document.
     *
     * @param document the GraphQL document containing the operation
     * @param operationName the name of the operation to normalize (null for anonymous operations)
     * @return a new document with the specified operation normalized
     * @throws OperationNotFoundException if no matching operation is found
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Document normalize(Document document, String operationName) {
        if (document == null) {
            throw new IllegalArgumentException("Document cannot be null");
        }

        OperationDefinition targetOperation = null;
        List<Definition> otherDefinitions = new ArrayList<>();

        for (var definition : document.getDefinitions()) {
            if (definition instanceof OperationDefinition operationDef) {
                String opName = operationDef.getName();
                boolean matches = (operationName == null && opName == null) ||
                                  (operationName != null && operationName.equals(opName));
                if (matches && targetOperation == null) {
                    targetOperation = operationDef;
                } else {
                    otherDefinitions.add(definition);
                }
            } else {
                otherDefinitions.add(definition);
            }
        }

        if (targetOperation == null) {
            throw new OperationNotFoundException(operationName);
        }

        List<Definition> forNormalization = new ArrayList<>();
        forNormalization.add(targetOperation);

        for (var definition : document.getDefinitions()) {
            if (definition instanceof FragmentDefinition) {
                forNormalization.add(definition);
            }
        }

        Document tempDoc = document.transform(builder ->
            builder.definitions(forNormalization)
        );

        Document normalized = normalize(tempDoc);

        OperationDefinition normalizedOperation = null;
        for (var definition : normalized.getDefinitions()) {
            if (definition instanceof OperationDefinition opDef) {
                normalizedOperation = opDef;
                break;
            }
        }

        List<Definition> finalDefinitions = new ArrayList<>();
        if (normalizedOperation != null) {
            finalDefinitions.add(normalizedOperation);
        }
        for (var definition : otherDefinitions) {
            if (definition instanceof OperationDefinition) {
                finalDefinitions.add(definition);
            }
        }

        return document.transform(builder -> builder.definitions(finalDefinitions));
    }

    // ==================== CORE SINGLE-PASS LOGIC ====================

    private OperationDefinition normalizeOperation(OperationDefinition operationDef,
                                                    Map<String, FragmentDefinition> fragments) {
        GraphQLCompositeType rootType = getRootType(operationDef);

        SelectionSet normalized = normalizeSelectionSet(
            operationDef.getSelectionSet(), rootType, fragments, new HashSet<>()
        );

        if (sortSelections) {
            List<VariableDefinition> sortedVars = sortVariableDefinitions(operationDef.getVariableDefinitions());
            List<Directive> sortedDirectives = sortDirectiveList(operationDef.getDirectives());
            return operationDef.transform(builder -> builder
                .variableDefinitions(sortedVars)
                .directives(sortedDirectives)
                .selectionSet(normalized)
            );
        }

        return operationDef.transform(builder -> builder.selectionSet(normalized));
    }

    /**
     * Core single-pass recursive method. For each selection set:
     * 1. Expand fragment spreads and filter by directives
     * 2. Recurse into children (bottom-up)
     * 3. Simplify type conditions
     * 4. Merge duplicates
     * 5. Sort
     */
    private SelectionSet normalizeSelectionSet(SelectionSet selectionSet,
                                                GraphQLCompositeType currentType,
                                                Map<String, FragmentDefinition> fragments,
                                                Set<String> visitedFragments) {
        if (selectionSet == null) {
            return null;
        }

        // Phase 1: Expand, filter, recurse, and simplify
        List<Selection<?>> expanded = new ArrayList<>();
        for (Selection<?> selection : selectionSet.getSelections()) {
            if (selection instanceof FragmentSpread spread) {
                expandFragmentSpread(spread, currentType, fragments, visitedFragments, expanded);
            } else if (selection instanceof InlineFragment inlineFragment) {
                processInlineFragment(inlineFragment, currentType, fragments, visitedFragments, expanded);
            } else if (selection instanceof Field field) {
                processField(field, currentType, fragments, visitedFragments, expanded);
            } else {
                expanded.add(selection);
            }
        }

        // Phase 2: Merge duplicates
        List<Selection<?>> merged = deduplicateFields ? mergeSelections(expanded) : expanded;

        // Phase 3: Sort
        List<Selection<?>> sorted = sortSelections ? sortSelectionList(merged) : merged;

        return SelectionSet.newSelectionSet()
            .selections(sorted)
            .build();
    }

    // ==================== PHASE 1: EXPAND, FILTER, RECURSE ====================

    private void expandFragmentSpread(FragmentSpread spread,
                                       GraphQLCompositeType currentType,
                                       Map<String, FragmentDefinition> fragments,
                                       Set<String> visitedFragments,
                                       List<Selection<?>> output) {
        if (!inlineFragments) {
            output.add(spread);
            return;
        }

        String fragmentName = spread.getName();

        if (visitedFragments.contains(fragmentName)) {
            throw new CircularFragmentException(fragmentName, visitedFragments);
        }

        FragmentDefinition fragment = fragments.get(fragmentName);
        if (fragment == null) {
            throw new FragmentNotFoundException(fragmentName);
        }

        Set<String> newVisited = new HashSet<>(visitedFragments);
        newVisited.add(fragmentName);

        List<Directive> mergedDirectives = mergeDirectiveLists(
            spread.getDirectives(), fragment.getDirectives()
        );

        TypeName typeCondition = fragment.getTypeCondition();
        InlineFragment asInline = InlineFragment.newInlineFragment()
            .typeCondition(typeCondition)
            .directives(mergedDirectives)
            .selectionSet(fragment.getSelectionSet())
            .build();

        processInlineFragment(asInline, currentType, fragments, newVisited, output);
    }

    private void processInlineFragment(InlineFragment inlineFragment,
                                        GraphQLCompositeType currentType,
                                        Map<String, FragmentDefinition> fragments,
                                        Set<String> visitedFragments,
                                        List<Selection<?>> output) {
        // Evaluate directives
        if (processSkipInclude) {
            DirectiveResult directiveResult = evaluateDirectives(inlineFragment.getDirectives());
            if (directiveResult.shouldRemove) {
                return;
            }
            inlineFragment = inlineFragment.transform(builder ->
                builder.directives(directiveResult.remainingDirectives)
            );
        }

        String typeConditionName = inlineFragment.getTypeCondition() != null
            ? inlineFragment.getTypeCondition().getName()
            : null;

        // Determine the type context for children
        GraphQLCompositeType fragmentType;
        if (typeConditionName != null) {
            GraphQLCompositeType resolved = getCompositeType(typeConditionName);
            fragmentType = resolved != null ? resolved : currentType;
        } else {
            fragmentType = currentType;
        }

        // Recurse into children
        SelectionSet normalizedChildren = normalizeSelectionSet(
            inlineFragment.getSelectionSet(), fragmentType, fragments, visitedFragments
        );

        // Type condition simplification: unwrap if redundant
        if (typeConditionName != null && currentType != null
                && isTypeConditionRedundant(currentType, typeConditionName)) {
            if (normalizedChildren != null) {
                for (Selection<?> child : normalizedChildren.getSelections()) {
                    output.add(child);
                }
            }
        } else {
            InlineFragment result = inlineFragment.transform(builder ->
                builder.selectionSet(normalizedChildren)
            );

            if (sortSelections) {
                List<Directive> sortedDirs = sortDirectiveList(result.getDirectives());
                result = result.transform(builder -> builder.directives(sortedDirs));
            }

            output.add(result);
        }
    }

    private void processField(Field field,
                               GraphQLCompositeType currentType,
                               Map<String, FragmentDefinition> fragments,
                               Set<String> visitedFragments,
                               List<Selection<?>> output) {
        // Evaluate directives
        if (processSkipInclude) {
            DirectiveResult directiveResult = evaluateDirectives(field.getDirectives());
            if (directiveResult.shouldRemove) {
                return;
            }
            field = field.transform(builder ->
                builder.directives(directiveResult.remainingDirectives)
            );
        }

        // Recurse into children
        if (field.getSelectionSet() != null) {
            GraphQLCompositeType fieldType = currentType != null
                ? getFieldReturnType(currentType, field.getName())
                : null;

            SelectionSet normalizedChildren = normalizeSelectionSet(
                field.getSelectionSet(), fieldType, fragments, visitedFragments
            );

            field = field.transform(builder -> builder.selectionSet(normalizedChildren));
        }

        // Sort arguments and directives
        if (sortSelections) {
            List<Argument> sortedArgs = sortArguments(field.getArguments());
            List<Directive> sortedDirs = sortDirectiveList(field.getDirectives());
            field = field.transform(builder -> builder
                .arguments(sortedArgs)
                .directives(sortedDirs)
            );
        }

        output.add(field);
    }

    // ==================== PHASE 2: MERGE ====================

    private List<Selection<?>> mergeSelections(List<Selection<?>> selections) {
        Map<String, Field> fieldsByResponseKey = new LinkedHashMap<>();
        Map<String, InlineFragment> fragmentsByType = new LinkedHashMap<>();
        List<Selection<?>> otherSelections = new ArrayList<>();

        for (Selection<?> selection : selections) {
            if (selection instanceof Field field) {
                String responseKey = getResponseKey(field);
                Field existing = fieldsByResponseKey.get(responseKey);
                if (existing != null) {
                    fieldsByResponseKey.put(responseKey, mergeFields(existing, field));
                } else {
                    fieldsByResponseKey.put(responseKey, field);
                }
            } else if (selection instanceof InlineFragment inlineFragment) {
                String typeKey = getTypeKey(inlineFragment);
                InlineFragment existing = fragmentsByType.get(typeKey);
                if (existing != null) {
                    fragmentsByType.put(typeKey, mergeInlineFragments(existing, inlineFragment));
                } else {
                    fragmentsByType.put(typeKey, inlineFragment);
                }
            } else {
                otherSelections.add(selection);
            }
        }

        List<Selection<?>> merged = new ArrayList<>();
        merged.addAll(fieldsByResponseKey.values());
        merged.addAll(fragmentsByType.values());
        merged.addAll(otherSelections);
        return merged;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Field mergeFields(Field field1, Field field2) {
        if (!field1.getName().equals(field2.getName())) {
            throw new FieldConflictException(
                getResponseKey(field1), field1.getName(), field2.getName()
            );
        }

        if (!argumentsMatch(field1.getArguments(), field2.getArguments())) {
            throw new ArgumentConflictException(
                getResponseKey(field1), field1.getArguments(), field2.getArguments()
            );
        }

        // Merge sub-selection sets, then locally re-merge + re-sort
        SelectionSet mergedSubs = combineSelectionSets(
            field1.getSelectionSet(), field2.getSelectionSet()
        );

        if (mergedSubs != null) {
            List<Selection<?>> combined = new ArrayList<>();
            for (Selection s : mergedSubs.getSelections()) {
                combined.add(s);
            }
            List<Selection<?>> reMerged = mergeSelections(combined);
            List<Selection<?>> reSorted = sortSelections ? sortSelectionList(reMerged) : reMerged;
            mergedSubs = SelectionSet.newSelectionSet().selections(reSorted).build();
        }

        List<Directive> mergedDirectives = mergeDirectivesByKey(
            field1.getDirectives(), field2.getDirectives()
        );

        SelectionSet finalMergedSubs = mergedSubs;
        return field1.transform(builder -> builder
            .selectionSet(finalMergedSubs)
            .directives(mergedDirectives)
        );
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private InlineFragment mergeInlineFragments(InlineFragment fragment1, InlineFragment fragment2) {
        SelectionSet mergedSubs = combineSelectionSets(
            fragment1.getSelectionSet(), fragment2.getSelectionSet()
        );

        if (mergedSubs != null) {
            List<Selection<?>> combined = new ArrayList<>();
            for (Selection s : mergedSubs.getSelections()) {
                combined.add(s);
            }
            List<Selection<?>> reMerged = mergeSelections(combined);
            List<Selection<?>> reSorted = sortSelections ? sortSelectionList(reMerged) : reMerged;
            mergedSubs = SelectionSet.newSelectionSet().selections(reSorted).build();
        }

        List<Directive> mergedDirectives = mergeDirectivesByKey(
            fragment1.getDirectives(), fragment2.getDirectives()
        );

        SelectionSet finalMergedSubs = mergedSubs;
        return fragment1.transform(builder -> builder
            .selectionSet(finalMergedSubs)
            .directives(mergedDirectives)
        );
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private SelectionSet combineSelectionSets(SelectionSet set1, SelectionSet set2) {
        if (set1 == null && set2 == null) {
            return null;
        }
        if (set1 == null) {
            return set2;
        }
        if (set2 == null) {
            return set1;
        }

        List<Selection<?>> combined = new ArrayList<>();
        for (Selection s : set1.getSelections()) {
            combined.add(s);
        }
        for (Selection s : set2.getSelections()) {
            combined.add(s);
        }

        return SelectionSet.newSelectionSet()
            .selections(combined)
            .build();
    }

    // ==================== PHASE 3: SORT ====================

    private List<Selection<?>> sortSelectionList(List<Selection<?>> selections) {
        List<Field> fields = new ArrayList<>();
        List<InlineFragment> inlineFragments = new ArrayList<>();
        List<Selection<?>> others = new ArrayList<>();

        for (Selection<?> selection : selections) {
            if (selection instanceof Field field) {
                fields.add(field);
            } else if (selection instanceof InlineFragment inlineFragment) {
                inlineFragments.add(inlineFragment);
            } else {
                others.add(selection);
            }
        }

        fields.sort(Comparator.comparing(this::getResponseKey));
        inlineFragments.sort(Comparator.comparing(this::getTypeConditionName));

        List<Selection<?>> sorted = new ArrayList<>();
        sorted.addAll(fields);
        sorted.addAll(inlineFragments);
        sorted.addAll(others);
        return sorted;
    }

    private List<Argument> sortArguments(List<Argument> arguments) {
        if (arguments == null || arguments.size() <= 1) {
            return arguments;
        }
        List<Argument> sorted = new ArrayList<>(arguments);
        sorted.sort(Comparator.comparing(Argument::getName));
        return sorted;
    }

    private List<Directive> sortDirectiveList(List<Directive> directives) {
        if (directives == null || directives.isEmpty()) {
            return directives;
        }

        List<Directive> sorted = new ArrayList<>(directives);
        sorted.sort(Comparator.comparing(Directive::getName));

        return sorted.stream()
            .map(d -> {
                List<Argument> sortedArgs = sortArguments(d.getArguments());
                return d.transform(builder -> builder.arguments(sortedArgs));
            })
            .toList();
    }

    private List<VariableDefinition> sortVariableDefinitions(List<VariableDefinition> variables) {
        if (variables == null || variables.size() <= 1) {
            return variables;
        }
        List<VariableDefinition> sorted = new ArrayList<>(variables);
        sorted.sort(Comparator.comparing(VariableDefinition::getName));
        return sorted;
    }

    // ==================== TYPE CONDITION SIMPLIFICATION ====================

    private boolean isTypeConditionRedundant(GraphQLCompositeType currentType, String typeConditionName) {
        if (currentType.getName().equals(typeConditionName)) {
            return true;
        }

        if (currentType instanceof GraphQLObjectType objectType) {
            GraphQLType conditionType = schema.getType(typeConditionName);
            if (conditionType instanceof GraphQLInterfaceType) {
                return objectType.getInterfaces().stream()
                    .anyMatch(iface -> iface.getName().equals(typeConditionName));
            }
            if (conditionType instanceof GraphQLUnionType unionType) {
                return unionType.getTypes().stream()
                    .anyMatch(member -> member.getName().equals(currentType.getName()));
            }
        }

        if (currentType instanceof GraphQLInterfaceType interfaceType) {
            GraphQLType conditionType = schema.getType(typeConditionName);
            if (conditionType instanceof GraphQLInterfaceType) {
                return interfaceType.getInterfaces().stream()
                    .anyMatch(iface -> iface.getName().equals(typeConditionName));
            }
        }

        return false;
    }

    private GraphQLCompositeType getRootType(OperationDefinition operationDef) {
        return switch (operationDef.getOperation()) {
            case QUERY -> schema.getQueryType();
            case MUTATION -> schema.getMutationType();
            case SUBSCRIPTION -> schema.getSubscriptionType();
        };
    }

    private GraphQLCompositeType getFieldReturnType(GraphQLCompositeType parentType, String fieldName) {
        if (IntrospectionFields.TYPENAME.equals(fieldName)) {
            return null;
        }

        GraphQLFieldDefinition fieldDef = null;
        if (parentType instanceof GraphQLObjectType objectType) {
            fieldDef = objectType.getFieldDefinition(fieldName);
        } else if (parentType instanceof GraphQLInterfaceType interfaceType) {
            fieldDef = interfaceType.getFieldDefinition(fieldName);
        }

        if (fieldDef == null) {
            return null;
        }

        GraphQLType unwrapped = unwrapAll(fieldDef.getType());
        if (unwrapped instanceof GraphQLCompositeType compositeType) {
            return compositeType;
        }

        return null;
    }

    private GraphQLCompositeType getCompositeType(String typeName) {
        GraphQLType type = schema.getType(typeName);
        if (type instanceof GraphQLCompositeType compositeType) {
            return compositeType;
        }
        return null;
    }

    // ==================== DIRECTIVE EVALUATION ====================

    private DirectiveResult evaluateDirectives(List<Directive> directives) {
        List<Directive> remaining = new ArrayList<>();
        boolean shouldRemove = false;

        for (Directive directive : directives) {
            String name = directive.getName();

            if (SKIP_DIRECTIVE.equals(name)) {
                Value<?> ifValue = getIfArgumentValue(directive);
                if (ifValue instanceof BooleanValue boolValue) {
                    if (boolValue.isValue()) {
                        shouldRemove = true;
                    }
                } else {
                    remaining.add(directive);
                }
            } else if (INCLUDE_DIRECTIVE.equals(name)) {
                Value<?> ifValue = getIfArgumentValue(directive);
                if (ifValue instanceof BooleanValue boolValue) {
                    if (!boolValue.isValue()) {
                        shouldRemove = true;
                    }
                } else {
                    remaining.add(directive);
                }
            } else {
                remaining.add(directive);
            }
        }

        return new DirectiveResult(shouldRemove, remaining);
    }

    private Value<?> getIfArgumentValue(Directive directive) {
        for (Argument argument : directive.getArguments()) {
            if (IF_ARGUMENT.equals(argument.getName())) {
                return argument.getValue();
            }
        }
        return BooleanValue.of(false);
    }

    private record DirectiveResult(boolean shouldRemove, List<Directive> remainingDirectives) {
    }

    // ==================== HELPERS ====================

    private Map<String, FragmentDefinition> collectFragments(Document document) {
        Map<String, FragmentDefinition> fragments = new HashMap<>();
        for (var definition : document.getDefinitions()) {
            if (definition instanceof FragmentDefinition fragmentDef) {
                fragments.put(fragmentDef.getName(), fragmentDef);
            }
        }
        return fragments;
    }

    private String getResponseKey(Field field) {
        return field.getAlias() != null ? field.getAlias() : field.getName();
    }

    private String getTypeKey(InlineFragment fragment) {
        TypeName typeCondition = fragment.getTypeCondition();
        return typeCondition != null ? typeCondition.getName() : "";
    }

    private String getTypeConditionName(InlineFragment fragment) {
        TypeName typeCondition = fragment.getTypeCondition();
        return typeCondition != null ? typeCondition.getName() : "";
    }

    private boolean argumentsMatch(List<Argument> args1, List<Argument> args2) {
        if (args1.size() != args2.size()) {
            return false;
        }

        Map<String, Argument> argMap1 = new LinkedHashMap<>();
        for (Argument arg : args1) {
            argMap1.put(arg.getName(), arg);
        }

        for (Argument arg2 : args2) {
            Argument arg1 = argMap1.get(arg2.getName());
            if (arg1 == null) {
                return false;
            }
            if (!valuesEqual(arg1.getValue(), arg2.getValue())) {
                return false;
            }
        }

        return true;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean valuesEqual(Value v1, Value v2) {
        if (v1 == null && v2 == null) {
            return true;
        }
        if (v1 == null || v2 == null) {
            return false;
        }
        return v1.isEqualTo(v2);
    }

    private List<Directive> mergeDirectiveLists(List<Directive> first, List<Directive> second) {
        if (first.isEmpty()) {
            return second;
        }
        if (second.isEmpty()) {
            return first;
        }
        List<Directive> merged = new ArrayList<>(first);
        merged.addAll(second);
        return merged;
    }

    private List<Directive> mergeDirectivesByKey(List<Directive> directives1, List<Directive> directives2) {
        if (directives1.isEmpty()) {
            return directives2;
        }
        if (directives2.isEmpty()) {
            return directives1;
        }

        Map<String, Directive> directiveMap = new LinkedHashMap<>();
        for (var directive : directives1) {
            directiveMap.put(directiveKey(directive), directive);
        }
        for (var directive : directives2) {
            directiveMap.putIfAbsent(directiveKey(directive), directive);
        }

        return new ArrayList<>(directiveMap.values());
    }

    private String directiveKey(Directive directive) {
        StringBuilder key = new StringBuilder(directive.getName());
        for (Argument arg : directive.getArguments()) {
            key.append(":").append(arg.getName()).append("=").append(arg.getValue());
        }
        return key.toString();
    }

    // ==================== PUBLIC CONFIGURATION QUERIES ====================

    public boolean isInlineFragmentsEnabled() {
        return inlineFragments;
    }

    public boolean isProcessSkipIncludeEnabled() {
        return processSkipInclude;
    }

    public boolean isDeduplicateFieldsEnabled() {
        return deduplicateFields;
    }

    public boolean isSortSelectionsEnabled() {
        return sortSelections;
    }

    // ==================== BUILDER ====================

    public static final class Builder {
        private boolean inlineFragments = true;
        private boolean processSkipInclude = true;
        private boolean deduplicateFields = true;
        private boolean sortSelections = true;
        private final GraphQLSchema schema;

        private Builder(GraphQLSchema schema) {
            if (schema == null) {
                throw new IllegalArgumentException("Schema cannot be null");
            }
            this.schema = schema;
        }

        public Builder inlineFragments(boolean enabled) {
            this.inlineFragments = enabled;
            return this;
        }

        public Builder processSkipInclude(boolean enabled) {
            this.processSkipInclude = enabled;
            return this;
        }

        public Builder deduplicateFields(boolean enabled) {
            this.deduplicateFields = enabled;
            return this;
        }

        public Builder sortSelections(boolean enabled) {
            this.sortSelections = enabled;
            return this;
        }

        public OperationNormalizer build() {
            return new OperationNormalizer(this);
        }
    }

    // ==================== EXCEPTION CLASSES ====================

    public static class OperationNotFoundException extends RuntimeException {
        private final String operationName;

        public OperationNotFoundException(String operationName) {
            super(operationName == null
                ? "No anonymous operation found in document"
                : "Operation '" + operationName + "' not found in document");
            this.operationName = operationName;
        }

        public String getOperationName() {
            return operationName;
        }
    }

    public static class CircularFragmentException extends RuntimeException {
        private final String fragmentName;
        private final Set<String> cycle;

        public CircularFragmentException(String fragmentName, Set<String> visitedFragments) {
            super("Circular fragment reference detected: fragment '" + fragmentName +
                  "' references itself through chain: " + visitedFragments);
            this.fragmentName = fragmentName;
            this.cycle = Set.copyOf(visitedFragments);
        }

        public String getFragmentName() {
            return fragmentName;
        }

        public Set<String> getCycle() {
            return cycle;
        }
    }

    public static class FragmentNotFoundException extends RuntimeException {
        private final String fragmentName;

        public FragmentNotFoundException(String fragmentName) {
            super("Fragment '" + fragmentName + "' is not defined");
            this.fragmentName = fragmentName;
        }

        public String getFragmentName() {
            return fragmentName;
        }
    }

    public static class FieldConflictException extends RuntimeException {
        private final String responseKey;
        private final String fieldName1;
        private final String fieldName2;

        public FieldConflictException(String responseKey, String fieldName1, String fieldName2) {
            super("Fields with response key '" + responseKey +
                  "' have conflicting field names: '" + fieldName1 + "' vs '" + fieldName2 + "'");
            this.responseKey = responseKey;
            this.fieldName1 = fieldName1;
            this.fieldName2 = fieldName2;
        }

        public String getResponseKey() {
            return responseKey;
        }

        public String getFieldName1() {
            return fieldName1;
        }

        public String getFieldName2() {
            return fieldName2;
        }
    }

    public static class ArgumentConflictException extends RuntimeException {
        private final String responseKey;
        private final List<Argument> arguments1;
        private final List<Argument> arguments2;

        public ArgumentConflictException(String responseKey, List<Argument> arguments1, List<Argument> arguments2) {
            super("Fields with response key '" + responseKey + "' have conflicting arguments");
            this.responseKey = responseKey;
            this.arguments1 = List.copyOf(arguments1);
            this.arguments2 = List.copyOf(arguments2);
        }

        public String getResponseKey() {
            return responseKey;
        }

        public List<Argument> getArguments1() {
            return arguments1;
        }

        public List<Argument> getArguments2() {
            return arguments2;
        }
    }
}
