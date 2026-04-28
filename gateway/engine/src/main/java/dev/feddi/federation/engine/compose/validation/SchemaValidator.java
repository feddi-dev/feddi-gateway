package dev.feddi.federation.engine.compose.validation;

import dev.feddi.federation.engine.compose.Subgraph;
import dev.feddi.federation.engine.graph.Graph;
import graphql.schema.GraphQLSchema;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates subgraphs using registered validation rules.
 */
public final class SchemaValidator {

    private final List<ValidationRule> rules;
    private final List<PostMergeValidationRule> postMergeRules;
    private final List<PostGraphValidationRule> postGraphRules;

    public SchemaValidator(List<ValidationRule> rules, List<PostMergeValidationRule> postMergeRules) {
        this(rules, postMergeRules, List.of());
    }

    public SchemaValidator(List<ValidationRule> rules, List<PostMergeValidationRule> postMergeRules,
                          List<PostGraphValidationRule> postGraphRules) {
        this.rules = new ArrayList<>(rules);
        this.postMergeRules = new ArrayList<>(postMergeRules);
        this.postGraphRules = new ArrayList<>(postGraphRules);
    }
    
    /**
     * Creates a validator with default rules.
     */
    public static SchemaValidator withDefaultRules() {
        return new SchemaValidator(getDefaultRules(), getDefaultPostMergeRules(), getDefaultPostGraphRules());
    }
    
    /**
     * Validates subgraphs for a specific phase.
     */
    public ValidationResult validate(List<Subgraph> subgraphs, ValidationPhase phase) {
        ValidationResult.Builder builder = ValidationResult.builder();
        
        for (ValidationRule rule : rules) {
            if (rule.phase() == phase) {
                ValidationResult result = rule.validate(subgraphs);
                for (Diagnostic diagnostic : result.diagnostics()) {
                    builder.addDiagnostic(diagnostic);
                }
            }
        }
        
        return builder.build();
    }
    
    /**
     * Validates the merged schema using post-merge rules.
     */
    public ValidationResult validatePostMerge(GraphQLSchema mergedSchema, List<Subgraph> subgraphs) {
        ValidationResult.Builder builder = ValidationResult.builder();

        for (PostMergeValidationRule rule : postMergeRules) {
            ValidationResult result = rule.validate(mergedSchema, subgraphs);
            for (Diagnostic diagnostic : result.diagnostics()) {
                builder.addDiagnostic(diagnostic);
            }
        }

        return builder.build();
    }

    /**
     * Validates the composed schema using post-graph rules.
     * This is the final validation phase that can check satisfiability.
     */
    public ValidationResult validatePostGraph(Graph graph, GraphQLSchema mergedSchema, List<Subgraph> subgraphs) {
        ValidationResult.Builder builder = ValidationResult.builder();

        for (PostGraphValidationRule rule : postGraphRules) {
            ValidationResult result = rule.validate(graph, mergedSchema, subgraphs);
            for (Diagnostic diagnostic : result.diagnostics()) {
                builder.addDiagnostic(diagnostic);
            }
        }

        return builder.build();
    }
    
    /**
     * Validates subgraphs for source schema and pre-merge phases.
     * Post-merge validation should be called separately after merging.
     */
    public ValidationResult validateAll(List<Subgraph> subgraphs) {
        ValidationResult result = ValidationResult.success();
        
        // Run source schema validation first
        result = result.merge(validate(subgraphs, ValidationPhase.SOURCE_SCHEMA));
        if (result.hasErrors()) {
            return result; // Stop if source schema validation fails
        }
        
        // Run pre-merge validation
        result = result.merge(validate(subgraphs, ValidationPhase.PRE_MERGE));
        
        return result;
    }
    
    /**
     * Gets the default validation rules.
     */
    private static List<ValidationRule> getDefaultRules() {
        List<ValidationRule> rules = new ArrayList<>();

        // Source schema rules - validate built-in types and directives first
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.TypeDefinitionInvalidRule());
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.FieldSelectionMapSyntaxRule());
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.IsInvalidUsageRule());
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.RequireInvalidUsageRule());
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.LookupReturnsListRule());
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.LookupReturnsNonNullableRule());
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.LookupUnionKeyFieldMissingRule());
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.LookupDuplicateRule());
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.QueryRootTypeInaccessibleRule());
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.ExternalUnusedRule());
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.UnreachableTypeRule());

        // @key validation rules
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.KeyInvalidFieldsRule());

        // @provides validation rules
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.ProvidesInvalidFieldsRule());

        // @is and @require field type validation
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.FieldArgumentTypeRule());

        // @is and @require semantic field validation (cross-schema)
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.IsInvalidFieldsRule());
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.RequireInvalidFieldsRule());
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.RequireOnNonEntityRule());

        // Root type naming rules
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.RootQueryUsedRule());
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.RootMutationUsedRule());
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.RootSubscriptionUsedRule());

        // @inaccessible rules
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.DisallowedInaccessibleRule());

        // @external collision rules
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.ExternalCollisionRule());
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.ExternalOnInterfaceRule());
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.ExternalFieldHasArgumentsRule());

        // @shareable validation rules
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.InvalidShareableUsageRule());

        // @override validation rules
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.OverrideFromSelfRule());
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.OverrideOnInterfaceRule());
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.OverrideSourceHasOverrideRule());

        // Pre-merge rules
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.InvalidFieldSharingRule());
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.TypeKindMismatchRule());
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.OutputFieldTypesNotMergeableRule());
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.FieldArgumentTypesNotMergeableRule());
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.InputFieldTypesNotMergeableRule());
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.InputFieldDefaultMismatchRule());
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.InputWithMissingRequiredFieldsRule());
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.FieldWithMissingRequiredArgumentRule());
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.ReferenceToInaccessibleTypeRule());
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.EnumTypeDefaultValueInaccessibleRule());
        // Note: ENUM_VALUES_MISMATCH is not implemented - the feddi Gateway merges enum values from
        // different subgraphs (union of values), which differs from strict spec interpretation

        // External pre-merge validation rules
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.ExternalMissingOnBaseRule());
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.ExternalTypeMismatchRule());
        // Note: EXTERNAL_ARGUMENT_MISSING, EXTERNAL_ARGUMENT_TYPE_MISMATCH, and
        // EXTERNAL_ARGUMENT_DEFAULT_MISMATCH are NOT implemented. The spec defines these rules
        // but they conflict with EXTERNAL_UNUSED and PROVIDES_FIELDS_HAS_ARGUMENTS:
        // - @external fields must be in @provides (EXTERNAL_UNUSED)
        // - @provides cannot reference fields with arguments (PROVIDES_FIELDS_HAS_ARGUMENTS)
        // Therefore, @external fields cannot have arguments, making those rules unreachable.
        // We enforce this with ExternalFieldHasArgumentsRule in the SOURCE_SCHEMA phase.

        return rules;
    }
    
    /**
     * Gets the default post-merge validation rules.
     */
    private static List<PostMergeValidationRule> getDefaultPostMergeRules() {
        List<PostMergeValidationRule> rules = new ArrayList<>();

        // Post-merge rules (spec 3.4)
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.NoQueriesRule());
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.EmptyMergedObjectTypeRule());
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.EmptyMergedInterfaceTypeRule());
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.EmptyMergedInputObjectTypeRule());
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.EmptyMergedEnumTypeRule());
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.EmptyMergedUnionTypeRule());
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.ImplementedByInaccessibleRule());
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.NonNullInputFieldIsInaccessibleRule());
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.InterfaceHasNoImplementationsRule());
        // Note: INTERFACE_FIELD_NO_IMPLEMENTATION is not implemented as a separate rule.
        // GraphQL-Java validates interface implementation during schema building, so this
        // check is redundant. Any violations result in SCHEMA_BUILD_ERROR.

        return rules;
    }

    /**
     * Gets the default post-graph validation rules.
     * These rules validate the composed schema after the planning graph has been built.
     */
    private static List<PostGraphValidationRule> getDefaultPostGraphRules() {
        List<PostGraphValidationRule> rules = new ArrayList<>();

        // Post-graph rules - satisfiability validation
        rules.add(new dev.feddi.federation.engine.compose.validation.rules.SatisfiabilityValidationRule());

        return rules;
    }
}
