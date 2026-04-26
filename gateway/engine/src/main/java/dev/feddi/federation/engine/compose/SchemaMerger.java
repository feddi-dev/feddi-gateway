package dev.feddi.federation.engine.compose;

import dev.feddi.federation.engine.Constants;

import static dev.feddi.federation.engine.compose.FederationDirectives.*;

import graphql.language.Directive;
import graphql.language.EnumTypeDefinition;
import graphql.language.EnumValueDefinition;
import graphql.language.FieldDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ScalarTypeDefinition;
import graphql.language.Type;
import graphql.language.TypeDefinition;
import graphql.language.TypeName;
import graphql.language.UnionTypeDefinition;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.FieldWiringEnvironment;
import graphql.schema.idl.InterfaceWiringEnvironment;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.ScalarWiringEnvironment;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.SchemaPrinter;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.schema.idl.UnionWiringEnvironment;
import graphql.schema.idl.WiringFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merges multiple GraphQL schemas into a single supergraph schema.
 * 
 * <p>This class operates at the SDL level using TypeDefinitionRegistry,
 * which allows for proper merging of types with the same name across subgraphs.
 */
public final class SchemaMerger {
    
    private static final Set<String> FEDERATION_DIRECTIVES = FederationDirectives.ALL;
    
    private static final Set<String> BUILT_IN_SCALARS = Set.of(
        "String", "Int", "Float", "Boolean", "ID"
    );
    
    private final SchemaParser schemaParser;
    private final SchemaPrinter schemaPrinter;
    
    public SchemaMerger() {
        this.schemaParser = new SchemaParser();
        this.schemaPrinter = new SchemaPrinter(SchemaPrinter.Options.defaultOptions()
            .includeDirectives(true)
            .includeScalarTypes(true)
            .includeSchemaDefinition(true));
    }
    
    /**
     * Merges two GraphQL schemas into one.
     *
     * @param first the first schema (base)
     * @param second the second schema to merge in
     * @return the merged schema
     */
    public GraphQLSchema merge(GraphQLSchema first, GraphQLSchema second) {
        // Normalize root types to Query/Mutation before converting to registry
        GraphQLSchema normalizedFirst = normalizeRootTypes(first);
        GraphQLSchema normalizedSecond = normalizeRootTypes(second);

        // Convert schemas to TypeDefinitionRegistry via SDL
        TypeDefinitionRegistry firstRegistry = toTypeDefinitionRegistry(normalizedFirst);
        TypeDefinitionRegistry secondRegistry = toTypeDefinitionRegistry(normalizedSecond);

        // Strip @internal types and fields BEFORE merging - they don't participate in merge
        // This is different from @inaccessible which is processed AFTER merging
        stripInternalFromRegistry(firstRegistry);
        stripInternalFromRegistry(secondRegistry);

        // Merge the registries
        TypeDefinitionRegistry mergedRegistry = mergeRegistries(firstRegistry, secondRegistry);

        // Remove @inaccessible types from the merged registry
        Set<String> removedTypes = removeInaccessibleTypes(mergedRegistry);

        // Remove fields that reference removed @inaccessible types
        removeFieldsReferencingTypes(mergedRegistry, removedTypes);

        // Update unions to remove members that reference removed types
        updateUnionMembers(mergedRegistry, removedTypes);

        // Remove federation directive definitions from the merged registry
        removeFederationDirectiveDefinitions(mergedRegistry);

        // Strip federation directives from all types (enum values, fields, arguments, etc.)
        stripFederationDirectivesFromRegistry(mergedRegistry);

        // Validate the merged registry for empty types before building
        validateMergedRegistry(mergedRegistry);

        // Build the merged schema
        return buildSchema(mergedRegistry);
    }

    /**
     * Normalizes a schema to use standard Query/Mutation root types.
     * If the schema uses custom root type names (e.g., ProductQueries instead of Query),
     * this creates a new schema with the fields copied to standard Query/Mutation types.
     */
    private GraphQLSchema normalizeRootTypes(GraphQLSchema schema) {
        var queryType = schema.getQueryType();
        var mutationType = schema.getMutationType();

        // Check if we need to normalize
        boolean needsQueryNormalization = queryType != null && !Constants.QUERY.equals(queryType.getName());
        boolean needsMutationNormalization = mutationType != null && !Constants.MUTATION.equals(mutationType.getName());

        if (!needsQueryNormalization && !needsMutationNormalization) {
            return schema; // Already using standard names
        }

        // Build new schema with normalized root types
        graphql.schema.GraphQLSchema.Builder builder = GraphQLSchema.newSchema(schema);

        if (needsQueryNormalization) {
            // Create a new Query type with fields from the custom query type
            var newQueryType = graphql.schema.GraphQLObjectType.newObject()
                .name(Constants.QUERY)
                .description(queryType.getDescription())
                .fields(queryType.getFieldDefinitions())
                .withDirectives(queryType.getDirectives().toArray(new graphql.schema.GraphQLDirective[0]))
                .build();
            builder.query(newQueryType);
        }

        if (needsMutationNormalization) {
            // Create a new Mutation type with fields from the custom mutation type
            var newMutationType = graphql.schema.GraphQLObjectType.newObject()
                .name(Constants.MUTATION)
                .description(mutationType.getDescription())
                .fields(mutationType.getFieldDefinitions())
                .withDirectives(mutationType.getDirectives().toArray(new graphql.schema.GraphQLDirective[0]))
                .build();
            builder.mutation(newMutationType);
        }

        return builder.build();
    }
    
    /**
     * Strips @internal types and fields from a registry BEFORE merging.
     * Unlike @inaccessible (which is global), @internal is local to its source schema
     * and elements marked @internal do not participate in schema merging at all.
     */
    @SuppressWarnings("rawtypes")
    private void stripInternalFromRegistry(TypeDefinitionRegistry registry) {
        // First, collect names of @internal types
        Set<String> internalTypes = new HashSet<>();
        for (TypeDefinition type : registry.types().values()) {
            if (hasDirective(type.getDirectives(), INTERNAL)) {
                internalTypes.add(type.getName());
            }
        }

        // Remove @internal types
        for (String typeName : internalTypes) {
            TypeDefinition type = registry.getTypeOrNull(typeName);
            if (type != null) {
                registry.remove(type);
            }
        }

        // Strip @internal fields from remaining object and interface types
        // Also remove fields that reference @internal types
        List<TypeDefinition> typesToUpdate = new ArrayList<>(registry.types().values());
        for (TypeDefinition type : typesToUpdate) {
            if (type instanceof ObjectTypeDefinition obj) {
                List<FieldDefinition> cleanFields = obj.getFieldDefinitions().stream()
                    .filter(f -> !hasDirective(f.getDirectives(), INTERNAL))
                    .filter(f -> !internalTypes.contains(typeName(f.getType())))
                    .toList();
                if (cleanFields.size() != obj.getFieldDefinitions().size()) {
                    ObjectTypeDefinition updated = obj.transform(builder ->
                        builder.fieldDefinitions(cleanFields));
                    registry.remove(obj);
                    registry.add(updated);
                }
            } else if (type instanceof InterfaceTypeDefinition iface) {
                List<FieldDefinition> cleanFields = iface.getFieldDefinitions().stream()
                    .filter(f -> !hasDirective(f.getDirectives(), INTERNAL))
                    .filter(f -> !internalTypes.contains(typeName(f.getType())))
                    .toList();
                if (cleanFields.size() != iface.getFieldDefinitions().size()) {
                    InterfaceTypeDefinition updated = iface.transform(builder ->
                        builder.definitions(cleanFields));
                    registry.remove(iface);
                    registry.add(updated);
                }
            } else if (type instanceof InputObjectTypeDefinition input) {
                List<InputValueDefinition> cleanFields = input.getInputValueDefinitions().stream()
                    .filter(f -> !hasDirective(f.getDirectives(), INTERNAL))
                    .filter(f -> !internalTypes.contains(typeName(f.getType())))
                    .toList();
                if (cleanFields.size() != input.getInputValueDefinitions().size()) {
                    InputObjectTypeDefinition updated = input.transform(builder ->
                        builder.inputValueDefinitions(cleanFields));
                    registry.remove(input);
                    registry.add(updated);
                }
            }
        }
    }

    /**
     * Removes types marked with @inaccessible from the registry.
     * @return the set of removed type names
     */
    @SuppressWarnings("rawtypes")
    private Set<String> removeInaccessibleTypes(TypeDefinitionRegistry registry) {
        List<TypeDefinition> typesToRemove = registry.types().values().stream()
            .filter(type -> hasDirective(type.getDirectives(), INACCESSIBLE))
            .toList();

        Set<String> removedTypeNames = new HashSet<>();
        for (TypeDefinition type : typesToRemove) {
            removedTypeNames.add(type.getName());
            registry.remove(type);
        }
        return removedTypeNames;
    }

    /**
     * Removes fields from object and interface types that reference removed @inaccessible types.
     */
    @SuppressWarnings("rawtypes")
    private void removeFieldsReferencingTypes(TypeDefinitionRegistry registry, Set<String> removedTypes) {
        if (removedTypes.isEmpty()) {
            return;
        }

        List<TypeDefinition> typesToUpdate = registry.types().values().stream()
            .filter(type -> type instanceof ObjectTypeDefinition || type instanceof InterfaceTypeDefinition)
            .toList();

        for (TypeDefinition type : typesToUpdate) {
            if (type instanceof ObjectTypeDefinition obj) {
                List<FieldDefinition> filteredFields = obj.getFieldDefinitions().stream()
                    .filter(field -> !removedTypes.contains(typeName(field.getType())))
                    .toList();
                if (filteredFields.size() != obj.getFieldDefinitions().size()) {
                    ObjectTypeDefinition updated = obj.transform(builder ->
                        builder.fieldDefinitions(filteredFields));
                    registry.remove(obj);
                    registry.add(updated);
                }
            } else if (type instanceof InterfaceTypeDefinition iface) {
                List<FieldDefinition> filteredFields = iface.getFieldDefinitions().stream()
                    .filter(field -> !removedTypes.contains(typeName(field.getType())))
                    .toList();
                if (filteredFields.size() != iface.getFieldDefinitions().size()) {
                    InterfaceTypeDefinition updated = iface.transform(builder ->
                        builder.definitions(filteredFields));
                    registry.remove(iface);
                    registry.add(updated);
                }
            }
        }
    }


    /**
     * Updates union type definitions to remove members that reference removed types.
     */
    @SuppressWarnings("rawtypes")
    private void updateUnionMembers(TypeDefinitionRegistry registry, Set<String> removedTypes) {
        if (removedTypes.isEmpty()) {
            return;
        }

        List<TypeDefinition> unionsToUpdate = registry.types().values().stream()
            .filter(type -> type instanceof UnionTypeDefinition)
            .filter(union -> {
                UnionTypeDefinition u = (UnionTypeDefinition) union;
                return u.getMemberTypes().stream()
                    .anyMatch(member -> removedTypes.contains(typeName(member)));
            })
            .toList();

        for (TypeDefinition type : unionsToUpdate) {
            UnionTypeDefinition union = (UnionTypeDefinition) type;
            List<Type> cleanMembers = union.getMemberTypes().stream()
                .filter(member -> !removedTypes.contains(typeName(member)))
                .toList();

            UnionTypeDefinition updated = UnionTypeDefinition.newUnionTypeDefinition()
                .name(union.getName())
                .description(union.getDescription())
                .directives(union.getDirectives().stream()
                    .filter(d -> !FEDERATION_DIRECTIVES.contains(((Directive) d).getName()))
                    .map(d -> (Directive) d)
                    .toList())
                .memberTypes(cleanMembers)
                .build();

            registry.remove(union);
            registry.add(updated);
        }
    }

    /**
     * Removes federation directive definitions from the registry.
     * Federation directives like @key, @lookup, @is should not be exposed in the public supergraph schema.
     */
    private void removeFederationDirectiveDefinitions(TypeDefinitionRegistry registry) {
        List<String> directivesToRemove = registry.getDirectiveDefinitions().keySet().stream()
            .filter(FEDERATION_DIRECTIVES::contains)
            .toList();

        for (String directiveName : directivesToRemove) {
            registry.getDirectiveDefinition(directiveName)
                .ifPresent(registry::remove);
        }

        // Also remove FieldSet scalar if it exists (used by @key directive)
        var fieldSetScalar = registry.scalars().get("FieldSet");
        if (fieldSetScalar != null) {
            registry.remove(fieldSetScalar);
        }
    }

    /**
     * Strips federation directives from all types in the registry.
     * This includes removing @inaccessible enum values, @internal/@inaccessible fields, etc.
     */
    @SuppressWarnings("rawtypes")
    private void stripFederationDirectivesFromRegistry(TypeDefinitionRegistry registry) {
        List<TypeDefinition> typesToProcess = new ArrayList<>(registry.types().values());

        for (TypeDefinition type : typesToProcess) {
            TypeDefinition<?> stripped = stripFederationDirectivesFromType(type);
            if (stripped != type) {
                registry.remove(type);
                registry.add(stripped);
            }
        }
    }

    /**
     * Validates the merged registry for empty types that would cause schema building to fail.
     * Throws MergeValidationException with specific error codes for each type of issue.
     */
    @SuppressWarnings("rawtypes")
    private void validateMergedRegistry(TypeDefinitionRegistry registry) {
        for (TypeDefinition type : registry.types().values()) {
            if (type instanceof ObjectTypeDefinition obj) {
                // Check for empty Query type (NO_QUERIES)
                if (Constants.QUERY.equals(obj.getName()) && obj.getFieldDefinitions().isEmpty()) {
                    throw new MergeValidationException("NO_QUERIES",
                        "The merged schema must have at least one accessible Query field");
                }
                // Check for empty object types (EMPTY_MERGED_OBJECT_TYPE)
                if (obj.getFieldDefinitions().isEmpty()) {
                    throw new MergeValidationException("EMPTY_MERGED_OBJECT_TYPE",
                        "Object type '" + obj.getName() + "' has no accessible fields after merging");
                }
            } else if (type instanceof InterfaceTypeDefinition iface) {
                // Check for empty interface types (EMPTY_MERGED_INTERFACE_TYPE)
                if (iface.getFieldDefinitions().isEmpty()) {
                    throw new MergeValidationException("EMPTY_MERGED_INTERFACE_TYPE",
                        "Interface type '" + iface.getName() + "' has no accessible fields after merging");
                }
            } else if (type instanceof InputObjectTypeDefinition input) {
                // Check for empty input types (EMPTY_MERGED_INPUT_OBJECT_TYPE)
                if (input.getInputValueDefinitions().isEmpty()) {
                    throw new MergeValidationException("EMPTY_MERGED_INPUT_OBJECT_TYPE",
                        "Input object type '" + input.getName() + "' has no accessible fields after merging");
                }
            } else if (type instanceof EnumTypeDefinition enumDef) {
                // Check for empty enum types (EMPTY_MERGED_ENUM_TYPE)
                if (enumDef.getEnumValueDefinitions().isEmpty()) {
                    throw new MergeValidationException("EMPTY_MERGED_ENUM_TYPE",
                        "Enum type '" + enumDef.getName() + "' has no accessible values after merging");
                }
            } else if (type instanceof UnionTypeDefinition union) {
                // Check for empty union types (EMPTY_MERGED_UNION_TYPE)
                if (union.getMemberTypes().isEmpty()) {
                    throw new MergeValidationException("EMPTY_MERGED_UNION_TYPE",
                        "Union type '" + union.getName() + "' has no accessible member types after merging");
                }
            }
        }
    }

    /**
     * Exception thrown when merge validation fails.
     * Contains a specific error code for the validation issue.
     */
    public static class MergeValidationException extends RuntimeException {
        private final String errorCode;

        public MergeValidationException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public String getErrorCode() {
            return errorCode;
        }
    }

    /**
     * Merges multiple schemas into one.
     * 
     * @param schemas the schemas to merge
     * @return the merged schema
     */
    public GraphQLSchema mergeAll(List<GraphQLSchema> schemas) {
        if (schemas.isEmpty()) {
            throw new IllegalArgumentException("At least one schema is required");
        }

        if (schemas.size() == 1) {
            // Even with a single schema, we need to strip federation directives
            return stripFederationDirectives(schemas.get(0));
        }

        GraphQLSchema result = schemas.get(0);
        for (int i = 1; i < schemas.size(); i++) {
            result = merge(result, schemas.get(i));
        }
        return result;
    }

    /**
     * Strips federation directives from a single schema.
     * Used when there's only one schema (no merging needed).
     */
    private GraphQLSchema stripFederationDirectives(GraphQLSchema schema) {
        GraphQLSchema normalized = normalizeRootTypes(schema);
        TypeDefinitionRegistry registry = toTypeDefinitionRegistry(normalized);

        // Strip @internal types and fields first (they don't participate in public schema)
        stripInternalFromRegistry(registry);

        // Collect names of @inaccessible types being skipped
        Set<String> inaccessibleTypes = new HashSet<>();
        registry.types().values().forEach(type -> {
            if (hasDirective(type.getDirectives(), INACCESSIBLE)) {
                inaccessibleTypes.add(type.getName());
            }
        });

        // Strip federation directives from all types, excluding @inaccessible types
        TypeDefinitionRegistry cleanRegistry = new TypeDefinitionRegistry();
        registry.types().values().forEach(type -> {
            // Skip @inaccessible types
            if (!hasDirective(type.getDirectives(), INACCESSIBLE)) {
                cleanRegistry.add(stripFederationDirectivesFromType(type));
            }
        });
        registry.scalars().values().forEach(scalarDef -> {
            if (!BUILT_IN_SCALARS.contains(scalarDef.getName()) && !"FieldSet".equals(scalarDef.getName())) {
                cleanRegistry.add(scalarDef);
            }
        });
        // Only add non-federation directive definitions
        registry.getDirectiveDefinitions().values().forEach(directiveDef -> {
            if (!FEDERATION_DIRECTIVES.contains(directiveDef.getName())) {
                cleanRegistry.add(directiveDef);
            }
        });
        registry.schemaDefinition().ifPresent(cleanRegistry::add);

        // Update unions to remove members that reference @inaccessible types
        updateUnionMembers(cleanRegistry, inaccessibleTypes);

        // Validate the merged registry for empty types before building
        validateMergedRegistry(cleanRegistry);

        return buildSchema(cleanRegistry);
    }
    
    /**
     * Converts a GraphQLSchema to a TypeDefinitionRegistry by printing to SDL and parsing.
     */
    private TypeDefinitionRegistry toTypeDefinitionRegistry(GraphQLSchema schema) {
        String sdl = schemaPrinter.print(schema);
        return schemaParser.parse(sdl);
    }
    
    /**
     * Merges two TypeDefinitionRegistries, handling type conflicts by merging types.
     * This is based on TypeDefinitionRegistry.merge() but handles duplicates.
     */
    private TypeDefinitionRegistry mergeRegistries(TypeDefinitionRegistry first, TypeDefinitionRegistry second) {
        TypeDefinitionRegistry merged = new TypeDefinitionRegistry();

        // Add all types from the first registry (keep original for merge logic to detect hidden fields)
        first.types().values().forEach(merged::add);
        first.scalars().values().forEach(scalarDef -> {
            if (!BUILT_IN_SCALARS.contains(scalarDef.getName())) {
                merged.add(scalarDef);
            }
        });
        first.getDirectiveDefinitions().values().forEach(merged::add);
        first.schemaDefinition().ifPresent(merged::add);
        
        // Add all type extensions from first
        first.objectTypeExtensions().forEach((name, exts) -> exts.forEach(merged::add));
        first.interfaceTypeExtensions().forEach((name, exts) -> exts.forEach(merged::add));
        first.unionTypeExtensions().forEach((name, exts) -> exts.forEach(merged::add));
        first.enumTypeExtensions().forEach((name, exts) -> exts.forEach(merged::add));
        first.scalarTypeExtensions().forEach((name, exts) -> exts.forEach(merged::add));
        first.inputObjectTypeExtensions().forEach((name, exts) -> exts.forEach(merged::add));
        first.getSchemaExtensionDefinitions().forEach(merged::add);
        
        // Merge types from second registry
        for (TypeDefinition<?> secondType : second.types().values()) {
            String typeName = secondType.getName();
            TypeDefinition<?> firstType = merged.getTypeOrNull(typeName);
            
            if (firstType == null) {
                // Type doesn't exist, add it (with federation directives stripped)
                merged.add(stripFederationDirectivesFromType(secondType));
            } else {
                // Type exists, merge them
                TypeDefinition<?> mergedType = mergeTypeDefinitions(firstType, secondType);
                merged.remove(firstType);
                merged.add(mergedType);
            }
        }
        
        // Merge scalars from second (add if not present)
        second.scalars().values().forEach(scalarDef -> {
            if (!BUILT_IN_SCALARS.contains(scalarDef.getName()) && 
                merged.getTypeOrNull(scalarDef.getName()) == null) {
                merged.add(scalarDef);
            }
        });
        
        // Merge directives from second (add if not present)
        second.getDirectiveDefinitions().values().forEach(directiveDef -> {
            if (merged.getDirectiveDefinition(directiveDef.getName()).isEmpty()) {
                merged.add(directiveDef);
            }
        });
        
        // Add type extensions from second
        second.objectTypeExtensions().forEach((name, exts) -> exts.forEach(merged::add));
        second.interfaceTypeExtensions().forEach((name, exts) -> exts.forEach(merged::add));
        second.unionTypeExtensions().forEach((name, exts) -> exts.forEach(merged::add));
        second.enumTypeExtensions().forEach((name, exts) -> exts.forEach(merged::add));
        second.scalarTypeExtensions().forEach((name, exts) -> exts.forEach(merged::add));
        second.inputObjectTypeExtensions().forEach((name, exts) -> exts.forEach(merged::add));
        second.getSchemaExtensionDefinitions().forEach(merged::add);

        // Final pass: strip federation directives from any types that weren't merged
        // (types that only existed in first registry still have their original directives)
        finalizeTypes(merged, second);

        return merged;
    }

    /**
     * Strips federation directives from types that only existed in the first registry
     * and weren't merged with types from the second registry.
     * Types marked @inaccessible are left as-is so removeInaccessibleTypes can handle them.
     */
    @SuppressWarnings("rawtypes")
    private void finalizeTypes(TypeDefinitionRegistry merged, TypeDefinitionRegistry second) {
        // Find types that only exist in merged (from first) and weren't touched during merge
        // Exclude @inaccessible types - they'll be removed by removeInaccessibleTypes
        List<TypeDefinition> typesToClean = merged.types().values().stream()
            .filter(type -> second.getTypeOrNull(type.getName()) == null)
            .filter(this::hasFederationDirective)
            .filter(type -> !hasDirective(type.getDirectives(), INACCESSIBLE))
            .toList();

        for (TypeDefinition type : typesToClean) {
            merged.remove(type);
            merged.add(stripFederationDirectivesFromType(type));
        }
    }

    /**
     * Checks if a type has any federation directives.
     */
    @SuppressWarnings("rawtypes")
    private boolean hasFederationDirective(TypeDefinition type) {
        return type.getDirectives().stream()
            .anyMatch(d -> FEDERATION_DIRECTIVES.contains(((Directive) d).getName()));
    }
    
    /**
     * Strips federation directives from a type definition.
     * Also excludes fields marked with @inaccessible or @internal.
     * This is used when adding types that don't need merging.
     */
    @SuppressWarnings("unchecked")
    private TypeDefinition<?> stripFederationDirectivesFromType(TypeDefinition<?> type) {
        if (type instanceof ObjectTypeDefinition obj) {
            List<Directive> cleanDirectives = obj.getDirectives().stream()
                .filter(d -> !FEDERATION_DIRECTIVES.contains(d.getName()))
                .toList();
            // Exclude @inaccessible and @internal fields, then strip federation directives from remaining
            List<FieldDefinition> cleanFields = obj.getFieldDefinitions().stream()
                .filter(f -> !isHiddenField(f))
                .map(this::stripFederationDirectivesFromField)
                .toList();

            if (cleanDirectives.equals(obj.getDirectives()) && cleanFields.equals(obj.getFieldDefinitions())) {
                return type;
            }

            return ObjectTypeDefinition.newObjectTypeDefinition()
                .name(obj.getName())
                .description(obj.getDescription())
                .implementz(obj.getImplements())
                .directives(cleanDirectives)
                .fieldDefinitions(cleanFields)
                .build();
        }

        if (type instanceof InterfaceTypeDefinition iface) {
            List<Directive> cleanDirectives = iface.getDirectives().stream()
                .filter(d -> !FEDERATION_DIRECTIVES.contains(d.getName()))
                .toList();
            // Filter out @inaccessible fields
            List<FieldDefinition> cleanFields = iface.getFieldDefinitions().stream()
                .filter(f -> !isHiddenField(f))
                .map(this::stripFederationDirectivesFromField)
                .toList();

            return InterfaceTypeDefinition.newInterfaceTypeDefinition()
                .name(iface.getName())
                .description(iface.getDescription())
                .directives(cleanDirectives)
                .definitions(cleanFields)
                .build();
        }

        if (type instanceof InputObjectTypeDefinition input) {
            List<Directive> cleanDirectives = input.getDirectives().stream()
                .filter(d -> !FEDERATION_DIRECTIVES.contains(d.getName()))
                .toList();
            // Filter out @inaccessible input fields
            List<InputValueDefinition> cleanFields = input.getInputValueDefinitions().stream()
                .filter(f -> !hasDirective(f.getDirectives(), INACCESSIBLE))
                .map(this::stripFederationDirectivesFromArg)
                .toList();

            return InputObjectTypeDefinition.newInputObjectDefinition()
                .name(input.getName())
                .description(input.getDescription())
                .directives(cleanDirectives)
                .inputValueDefinitions(cleanFields)
                .build();
        }

        if (type instanceof EnumTypeDefinition enumDef) {
            List<Directive> cleanDirectives = enumDef.getDirectives().stream()
                .filter(d -> !FEDERATION_DIRECTIVES.contains(d.getName()))
                .toList();
            // Filter out @inaccessible enum values
            List<EnumValueDefinition> cleanValues = enumDef.getEnumValueDefinitions().stream()
                .filter(v -> !hasDirective(v.getDirectives(), INACCESSIBLE))
                .toList();

            return EnumTypeDefinition.newEnumTypeDefinition()
                .name(enumDef.getName())
                .description(enumDef.getDescription())
                .directives(cleanDirectives)
                .enumValueDefinitions(cleanValues)
                .build();
        }

        if (type instanceof UnionTypeDefinition union) {
            List<Directive> cleanDirectives = union.getDirectives().stream()
                .filter(d -> !FEDERATION_DIRECTIVES.contains(d.getName()))
                .toList();

            if (cleanDirectives.equals(union.getDirectives())) {
                return type;
            }

            return UnionTypeDefinition.newUnionTypeDefinition()
                .name(union.getName())
                .description(union.getDescription())
                .directives(cleanDirectives)
                .memberTypes(union.getMemberTypes())
                .build();
        }

        // For other types (ScalarTypeDefinition, etc.), return as-is
        return type;
    }

    /**
     * Merges two type definitions with the same name.
     * If either type has @inaccessible, the merged type will have @inaccessible.
     */
    @SuppressWarnings("unchecked")
    private TypeDefinition<?> mergeTypeDefinitions(TypeDefinition<?> first, TypeDefinition<?> second) {
        // Both must be the same kind of type
        if (first.getClass() != second.getClass()) {
            throw new SchemaMergeException(
                "Cannot merge types with same name but different kinds: " +
                first.getName() + " (" + first.getClass().getSimpleName() + " vs " +
                second.getClass().getSimpleName() + ")"
            );
        }

        // Check if either type is @inaccessible - if so, merged result should be too
        boolean shouldBeInaccessible = hasDirective(first.getDirectives(), INACCESSIBLE)
            || hasDirective(second.getDirectives(), INACCESSIBLE);

        TypeDefinition<?> merged;
        if (first instanceof ObjectTypeDefinition firstObj && second instanceof ObjectTypeDefinition secondObj) {
            merged = mergeObjectTypeDefinitions(firstObj, secondObj);
        } else if (first instanceof InterfaceTypeDefinition firstIface && second instanceof InterfaceTypeDefinition secondIface) {
            merged = mergeInterfaceTypeDefinitions(firstIface, secondIface);
        } else if (first instanceof InputObjectTypeDefinition firstInput && second instanceof InputObjectTypeDefinition secondInput) {
            merged = mergeInputObjectTypeDefinitions(firstInput, secondInput);
        } else if (first instanceof EnumTypeDefinition firstEnum && second instanceof EnumTypeDefinition secondEnum) {
            merged = mergeEnumTypeDefinitions(firstEnum, secondEnum);
        } else if (first instanceof UnionTypeDefinition firstUnion && second instanceof UnionTypeDefinition secondUnion) {
            merged = mergeUnionTypeDefinitions(firstUnion, secondUnion);
        } else if (first instanceof ScalarTypeDefinition firstScalar && second instanceof ScalarTypeDefinition) {
            merged = mergeScalarTypeDefinitions(firstScalar, (ScalarTypeDefinition) second);
        } else {
            throw new SchemaMergeException(
                "Unsupported type kind for merging: " + first.getClass().getSimpleName() +
                " (type: " + first.getName() + ")");
        }

        // Ensure @inaccessible is on merged type if it was on either source
        if (shouldBeInaccessible && !hasDirective(merged.getDirectives(), INACCESSIBLE)) {
            merged = addInaccessibleDirective(merged);
        }

        return merged;
    }

    /**
     * Merges two ScalarTypeDefinitions.
     */
    private ScalarTypeDefinition mergeScalarTypeDefinitions(ScalarTypeDefinition first, ScalarTypeDefinition second) {
        List<Directive> mergedDirectives = mergeDirectives(first.getDirectives(), second.getDirectives());
        return first.transform(builder -> builder.directives(mergedDirectives));
    }

    /**
     * Adds @inaccessible directive to a type definition.
     */
    @SuppressWarnings("unchecked")
    private <T extends TypeDefinition<?>> T addInaccessibleDirective(T type) {
        List<Directive> directives = new ArrayList<>(type.getDirectives());
        directives.add(Directive.newDirective().name(INACCESSIBLE).build());

        if (type instanceof ObjectTypeDefinition obj) {
            return (T) obj.transform(b -> b.directives(directives));
        } else if (type instanceof InterfaceTypeDefinition iface) {
            return (T) iface.transform(b -> b.directives(directives));
        } else if (type instanceof UnionTypeDefinition union) {
            return (T) union.transform(b -> b.directives(directives));
        } else if (type instanceof EnumTypeDefinition enumDef) {
            return (T) enumDef.transform(b -> b.directives(directives));
        } else if (type instanceof InputObjectTypeDefinition input) {
            return (T) input.transform(b -> b.directives(directives));
        } else if (type instanceof ScalarTypeDefinition scalar) {
            return (T) scalar.transform(b -> b.directives(directives));
        }
        return type;
    }
    
    /**
     * Merges two ObjectTypeDefinitions by combining their fields.
     */
    private ObjectTypeDefinition mergeObjectTypeDefinitions(ObjectTypeDefinition first, ObjectTypeDefinition second) {
        // Track which fields should be completely excluded (@internal/@inaccessible from either schema)
        // These are hidden from the supergraph regardless of how they appear in other schemas
        Set<String> hiddenFields = new HashSet<>();
        for (FieldDefinition field : first.getFieldDefinitions()) {
            if (isHiddenField(field)) {
                hiddenFields.add(field.getName());
            }
        }
        for (FieldDefinition field : second.getFieldDefinitions()) {
            if (isHiddenField(field)) {
                hiddenFields.add(field.getName());
            }
        }
        
        // Merge fields, handling external fields and excluding hidden fields
        Map<String, FieldDefinition> mergedFields = new LinkedHashMap<>();
        for (FieldDefinition field : first.getFieldDefinitions()) {
            String fieldName = field.getName();
            if (hiddenFields.contains(fieldName)) {
                continue;
            }
            // External fields are only added if we don't have a non-external version
            if (hasDirective(field.getDirectives(), EXTERNAL)) {
                continue; // Skip external fields in first pass, let non-external from second win
            }
            mergedFields.put(fieldName, stripFederationDirectivesFromField(field));
        }
        for (FieldDefinition field : second.getFieldDefinitions()) {
            String fieldName = field.getName();
            if (hiddenFields.contains(fieldName)) {
                continue;
            }
            boolean isExternal = hasDirective(field.getDirectives(), EXTERNAL);
            if (!mergedFields.containsKey(fieldName)) {
                // Field not present yet - add it (even if external, as it may be the only source)
                if (!isExternal) {
                    mergedFields.put(fieldName, stripFederationDirectivesFromField(field));
                }
            } else if (!isExternal) {
                // Field exists and new one is non-external - merge (prefer non-external)
                FieldDefinition existingField = mergedFields.get(fieldName);
                mergedFields.put(fieldName, stripFederationDirectivesFromField(
                    mergeFieldDefinitions(existingField, field)));
            }
        }
        // Final pass: add any external fields that weren't replaced by non-external versions
        for (FieldDefinition field : first.getFieldDefinitions()) {
            String fieldName = field.getName();
            if (hiddenFields.contains(fieldName)) {
                continue;
            }
            if (hasDirective(field.getDirectives(), EXTERNAL) && !mergedFields.containsKey(fieldName)) {
                mergedFields.put(fieldName, stripFederationDirectivesFromField(field));
            }
        }
        for (FieldDefinition field : second.getFieldDefinitions()) {
            String fieldName = field.getName();
            if (hiddenFields.contains(fieldName)) {
                continue;
            }
            if (hasDirective(field.getDirectives(), EXTERNAL) && !mergedFields.containsKey(fieldName)) {
                mergedFields.put(fieldName, stripFederationDirectivesFromField(field));
            }
        }
        
        // Merge interfaces
        Set<Type> mergedInterfaces = new LinkedHashSet<>(first.getImplements());
        for (Type iface : second.getImplements()) {
            if (mergedInterfaces.stream().noneMatch(t -> typeName(t).equals(typeName(iface)))) {
                mergedInterfaces.add(iface);
            }
        }
        
        // Merge directives (exclude federation directives for supergraph)
        List<Directive> mergedDirectives = mergeDirectives(first.getDirectives(), second.getDirectives());
        
        return ObjectTypeDefinition.newObjectTypeDefinition()
            .name(first.getName())
            .description(first.getDescription() != null ? first.getDescription() : second.getDescription())
            .implementz(new ArrayList<>(mergedInterfaces))
            .directives(mergedDirectives)
            .fieldDefinitions(new ArrayList<>(mergedFields.values()))
            .build();
    }
    
    /**
     * Checks if a field is hidden from the supergraph (@internal or @inaccessible).
     * If ANY schema marks a field with these, it should be excluded.
     */
    private boolean isHiddenField(FieldDefinition field) {
        return hasDirective(field.getDirectives(), INTERNAL) ||
               hasDirective(field.getDirectives(), INACCESSIBLE);
    }

    /**
     * Checks if an input field is hidden from the supergraph (@internal or @inaccessible).
     * If ANY schema marks a field with these, it should be excluded.
     */
    private boolean isHiddenInputField(InputValueDefinition field) {
        return hasDirective(field.getDirectives(), INTERNAL) ||
               hasDirective(field.getDirectives(), INACCESSIBLE);
    }

    /**
     * Strips federation directives from a field for the supergraph.
     * Also removes arguments marked with @require as per the GraphQL Composite Schemas spec:
     * "Arguments annotated with the @require directive are removed from the composite schema
     * and the value for these will be resolved by the distributed executor."
     */
    private FieldDefinition stripFederationDirectivesFromField(FieldDefinition field) {
        List<Directive> cleanDirectives = field.getDirectives().stream()
            .filter(d -> !FEDERATION_DIRECTIVES.contains(d.getName()))
            .toList();

        // Remove @require arguments entirely, strip federation directives from remaining
        List<InputValueDefinition> cleanArgs = field.getInputValueDefinitions().stream()
            .filter(arg -> !hasDirective(arg.getDirectives(), REQUIRE))
            .map(this::stripFederationDirectivesFromArg)
            .toList();

        if (cleanDirectives.equals(field.getDirectives()) && cleanArgs.equals(field.getInputValueDefinitions())) {
            return field; // No changes needed
        }

        return FieldDefinition.newFieldDefinition()
            .name(field.getName())
            .type(field.getType())
            .description(field.getDescription())
            .directives(cleanDirectives)
            .inputValueDefinitions(cleanArgs)
            .build();
    }
    
    /**
     * Strips federation directives from an argument.
     */
    private InputValueDefinition stripFederationDirectivesFromArg(InputValueDefinition arg) {
        List<Directive> cleanDirectives = arg.getDirectives().stream()
            .filter(d -> !FEDERATION_DIRECTIVES.contains(d.getName()))
            .toList();
        
        if (cleanDirectives.equals(arg.getDirectives())) {
            return arg; // No changes needed
        }
        
        return InputValueDefinition.newInputValueDefinition()
            .name(arg.getName())
            .type(arg.getType())
            .description(arg.getDescription())
            .directives(cleanDirectives)
            .defaultValue(arg.getDefaultValue())
            .build();
    }
    
    /**
     * Merges two InterfaceTypeDefinitions by combining their fields.
     * If a field is marked @inaccessible or @internal in ANY schema, it should be hidden
     * from the supergraph, even if other schemas define it without those directives.
     */
    private InterfaceTypeDefinition mergeInterfaceTypeDefinitions(InterfaceTypeDefinition first, InterfaceTypeDefinition second) {
        // Track which fields should be completely excluded (@internal/@inaccessible from either schema)
        Set<String> hiddenFields = new HashSet<>();
        for (FieldDefinition field : first.getFieldDefinitions()) {
            if (isHiddenField(field)) {
                hiddenFields.add(field.getName());
            }
        }
        for (FieldDefinition field : second.getFieldDefinitions()) {
            if (isHiddenField(field)) {
                hiddenFields.add(field.getName());
            }
        }

        // Merge fields, excluding hidden fields and stripping federation directives
        Map<String, FieldDefinition> mergedFields = new LinkedHashMap<>();
        for (FieldDefinition field : first.getFieldDefinitions()) {
            String fieldName = field.getName();
            if (!hiddenFields.contains(fieldName)) {
                mergedFields.put(fieldName, stripFederationDirectivesFromField(field));
            }
        }
        for (FieldDefinition field : second.getFieldDefinitions()) {
            String fieldName = field.getName();
            if (hiddenFields.contains(fieldName)) {
                continue;
            }
            if (!mergedFields.containsKey(fieldName)) {
                mergedFields.put(fieldName, stripFederationDirectivesFromField(field));
            } else {
                mergedFields.put(fieldName, stripFederationDirectivesFromField(
                    mergeFieldDefinitions(mergedFields.get(fieldName), field)));
            }
        }

        // Merge directives
        List<Directive> mergedDirectives = mergeDirectives(first.getDirectives(), second.getDirectives());

        return InterfaceTypeDefinition.newInterfaceTypeDefinition()
            .name(first.getName())
            .description(first.getDescription() != null ? first.getDescription() : second.getDescription())
            .directives(mergedDirectives)
            .definitions(new ArrayList<>(mergedFields.values()))
            .build();
    }
    
    /**
     * Merges two InputObjectTypeDefinitions by combining their fields.
     * If an input field is marked @inaccessible or @internal in ANY schema, it should be hidden
     * from the supergraph, even if other schemas define it without those directives.
     */
    private InputObjectTypeDefinition mergeInputObjectTypeDefinitions(InputObjectTypeDefinition first, InputObjectTypeDefinition second) {
        // Track which input fields are hidden (@inaccessible or @internal) in either schema
        Set<String> hiddenFields = new HashSet<>();
        for (InputValueDefinition field : first.getInputValueDefinitions()) {
            if (isHiddenInputField(field)) {
                hiddenFields.add(field.getName());
            }
        }
        for (InputValueDefinition field : second.getInputValueDefinitions()) {
            if (isHiddenInputField(field)) {
                hiddenFields.add(field.getName());
            }
        }

        // Merge input fields, excluding hidden ones and stripping federation directives
        Map<String, InputValueDefinition> mergedFields = new LinkedHashMap<>();
        for (InputValueDefinition field : first.getInputValueDefinitions()) {
            if (!hiddenFields.contains(field.getName())) {
                mergedFields.put(field.getName(), stripFederationDirectivesFromArg(field));
            }
        }
        for (InputValueDefinition field : second.getInputValueDefinitions()) {
            if (!mergedFields.containsKey(field.getName()) && !hiddenFields.contains(field.getName())) {
                mergedFields.put(field.getName(), stripFederationDirectivesFromArg(field));
            }
        }

        // Merge directives
        List<Directive> mergedDirectives = mergeDirectives(first.getDirectives(), second.getDirectives());

        return InputObjectTypeDefinition.newInputObjectDefinition()
            .name(first.getName())
            .description(first.getDescription() != null ? first.getDescription() : second.getDescription())
            .directives(mergedDirectives)
            .inputValueDefinitions(new ArrayList<>(mergedFields.values()))
            .build();
    }
    
    /**
     * Merges two EnumTypeDefinitions by combining their values.
     *
     * If an enum value is marked @inaccessible in ANY schema, it should be hidden
     * from the supergraph, even if other schemas define it without @inaccessible.
     */
    private EnumTypeDefinition mergeEnumTypeDefinitions(EnumTypeDefinition first, EnumTypeDefinition second) {
        // Track which values are @inaccessible in either schema
        Set<String> inaccessibleValues = new HashSet<>();
        for (EnumValueDefinition value : first.getEnumValueDefinitions()) {
            if (hasDirective(value.getDirectives(), INACCESSIBLE)) {
                inaccessibleValues.add(value.getName());
            }
        }
        for (EnumValueDefinition value : second.getEnumValueDefinitions()) {
            if (hasDirective(value.getDirectives(), INACCESSIBLE)) {
                inaccessibleValues.add(value.getName());
            }
        }

        // Merge enum values, preserving @inaccessible from either schema
        Map<String, EnumValueDefinition> mergedValues = new LinkedHashMap<>();
        for (EnumValueDefinition value : first.getEnumValueDefinitions()) {
            if (inaccessibleValues.contains(value.getName())) {
                // Ensure the merged value has @inaccessible directive
                mergedValues.put(value.getName(), ensureInaccessible(value));
            } else {
                mergedValues.put(value.getName(), value);
            }
        }
        for (EnumValueDefinition value : second.getEnumValueDefinitions()) {
            if (!mergedValues.containsKey(value.getName())) {
                if (inaccessibleValues.contains(value.getName())) {
                    mergedValues.put(value.getName(), ensureInaccessible(value));
                } else {
                    mergedValues.put(value.getName(), value);
                }
            }
        }

        // Merge directives
        List<Directive> mergedDirectives = mergeDirectives(first.getDirectives(), second.getDirectives());

        return EnumTypeDefinition.newEnumTypeDefinition()
            .name(first.getName())
            .description(first.getDescription() != null ? first.getDescription() : second.getDescription())
            .directives(mergedDirectives)
            .enumValueDefinitions(new ArrayList<>(mergedValues.values()))
            .build();
    }

    /**
     * Ensures an enum value has the @inaccessible directive.
     */
    private EnumValueDefinition ensureInaccessible(EnumValueDefinition value) {
        if (hasDirective(value.getDirectives(), INACCESSIBLE)) {
            return value;
        }
        List<Directive> directives = new ArrayList<>(value.getDirectives());
        directives.add(Directive.newDirective().name(INACCESSIBLE).build());
        return value.transform(builder -> builder.directives(directives));
    }
    
    /**
     * Merges two UnionTypeDefinitions by combining their member types.
     */
    private UnionTypeDefinition mergeUnionTypeDefinitions(UnionTypeDefinition first, UnionTypeDefinition second) {
        // Merge member types
        Set<String> addedTypes = new HashSet<>();
        List<Type> mergedMemberTypes = new ArrayList<>();
        
        for (Type type : first.getMemberTypes()) {
            mergedMemberTypes.add(type);
            addedTypes.add(typeName(type));
        }
        
        for (Type type : second.getMemberTypes()) {
            if (!addedTypes.contains(typeName(type))) {
                mergedMemberTypes.add(type);
            }
        }
        
        // Merge directives
        List<Directive> mergedDirectives = mergeDirectives(first.getDirectives(), second.getDirectives());
        
        return UnionTypeDefinition.newUnionTypeDefinition()
            .name(first.getName())
            .description(first.getDescription() != null ? first.getDescription() : second.getDescription())
            .directives(mergedDirectives)
            .memberTypes(mergedMemberTypes)
            .build();
    }
    
    /**
     * Merges two field definitions.
     * Prefers non-external fields over external ones.
     * Also merges arguments from both definitions (spec 3.3.9/3.3.10).
     * If an argument is marked @inaccessible in ANY schema, it should be hidden
     * from the supergraph, even if other schemas define it without @inaccessible.
     * Arguments marked with @require are removed entirely from the composite schema.
     */
    private FieldDefinition mergeFieldDefinitions(FieldDefinition first, FieldDefinition second) {
        boolean firstIsExternal = hasDirective(first.getDirectives(), EXTERNAL);
        boolean secondIsExternal = hasDirective(second.getDirectives(), EXTERNAL);

        // Determine the base field (prefer non-external)
        FieldDefinition base;
        FieldDefinition other;
        if (firstIsExternal && !secondIsExternal) {
            base = second;
            other = first;
        } else if (!firstIsExternal && secondIsExternal) {
            base = first;
            other = second;
        } else {
            // Both are the same (both external or both non-external) - use first as base
            base = first;
            other = second;
        }

        // Track which arguments are @inaccessible or @require in either definition
        // Both types should be excluded from the supergraph
        Set<String> excludedArgs = new HashSet<>();
        for (InputValueDefinition arg : first.getInputValueDefinitions()) {
            if (hasDirective(arg.getDirectives(), INACCESSIBLE) ||
                hasDirective(arg.getDirectives(), REQUIRE)) {
                excludedArgs.add(arg.getName());
            }
        }
        for (InputValueDefinition arg : second.getInputValueDefinitions()) {
            if (hasDirective(arg.getDirectives(), INACCESSIBLE) ||
                hasDirective(arg.getDirectives(), REQUIRE)) {
                excludedArgs.add(arg.getName());
            }
        }

        // Merge arguments from both definitions, excluding @inaccessible and @require ones
        Map<String, InputValueDefinition> mergedArgs = new LinkedHashMap<>();
        for (InputValueDefinition arg : base.getInputValueDefinitions()) {
            if (!excludedArgs.contains(arg.getName())) {
                mergedArgs.put(arg.getName(), arg);
            }
        }
        for (InputValueDefinition arg : other.getInputValueDefinitions()) {
            if (!mergedArgs.containsKey(arg.getName()) && !excludedArgs.contains(arg.getName())) {
                mergedArgs.put(arg.getName(), arg);
            }
        }

        // Build merged field with combined arguments (minus excluded ones)
        return FieldDefinition.newFieldDefinition()
            .name(base.getName())
            .type(base.getType())
            .description(base.getDescription())
            .directives(base.getDirectives())
            .inputValueDefinitions(new ArrayList<>(mergedArgs.values()))
            .build();
    }
    
    /**
     * Merges directive lists, excluding federation-specific directives.
     */
    private List<Directive> mergeDirectives(List<Directive> first, List<Directive> second) {
        Map<String, Directive> merged = new LinkedHashMap<>();
        
        for (Directive directive : first) {
            if (!shouldExcludeFromSupergraph(directive.getName())) {
                merged.put(directive.getName(), directive);
            }
        }
        
        for (Directive directive : second) {
            String name = directive.getName();
            if (!shouldExcludeFromSupergraph(name) && !merged.containsKey(name)) {
                merged.put(name, directive);
            }
        }
        
        return new ArrayList<>(merged.values());
    }
    
    /**
     * Checks if a directive should be excluded from the supergraph schema.
     */
    private boolean shouldExcludeFromSupergraph(String directiveName) {
        return FEDERATION_DIRECTIVES.contains(directiveName);
    }
    
    /**
     * Gets the name from a Type.
     */
    private String typeName(Type type) {
        if (type instanceof TypeName typeName) {
            return typeName.getName();
        }
        if (type instanceof NonNullType nonNull) {
            return typeName(nonNull.getType());
        }
        if (type instanceof ListType list) {
            return typeName(list.getType());
        }
        throw new IllegalArgumentException("Unknown type: " + type);
    }
    
    /**
     * Checks if a directive list contains a directive with the given name.
     */
    private boolean hasDirective(List<Directive> directives, String name) {
        return directives.stream().anyMatch(d -> d.getName().equals(name));
    }
    
    /**
     * Builds a GraphQLSchema from a TypeDefinitionRegistry.
     */
    private GraphQLSchema buildSchema(TypeDefinitionRegistry registry) {
        RuntimeWiring wiring = RuntimeWiring.newRuntimeWiring()
            .wiringFactory(new NoOpWiringFactory())
            .build();
        
        return new SchemaGenerator().makeExecutableSchema(registry, wiring);
    }
    
    /**
     * A WiringFactory that provides no-op implementations for building the schema.
     */
    private static class NoOpWiringFactory implements WiringFactory {
        @Override
        public boolean providesTypeResolver(InterfaceWiringEnvironment environment) {
            return true;
        }

        @Override
        public graphql.schema.TypeResolver getTypeResolver(InterfaceWiringEnvironment environment) {
            return env -> null;
        }

        @Override
        public boolean providesTypeResolver(UnionWiringEnvironment environment) {
            return true;
        }

        @Override
        public graphql.schema.TypeResolver getTypeResolver(UnionWiringEnvironment environment) {
            return env -> null;
        }

        @Override
        public boolean providesDataFetcher(FieldWiringEnvironment environment) {
            return true;
        }

        @Override
        public graphql.schema.DataFetcher<?> getDataFetcher(FieldWiringEnvironment environment) {
            return env -> null;
        }
        
        @Override
        public boolean providesScalar(ScalarWiringEnvironment environment) {
            return CustomScalarWiring.isCustomScalar(environment.getScalarTypeDefinition().getName());
        }

        @Override
        public graphql.schema.GraphQLScalarType getScalar(ScalarWiringEnvironment environment) {
            return CustomScalarWiring.passThrough(environment.getScalarTypeDefinition().getName());
        }
    }
    
    /**
     * Exception thrown when schema merging fails.
     */
    public static class SchemaMergeException extends RuntimeException {
        public SchemaMergeException(String message) {
            super(message);
        }
        
        public SchemaMergeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
