# Skipped Fusion planner tests

| Test | Reason |
|---|---|
| AbstractLookupFanoutPlanningTests.Plan_Should_Attribute_Nested_Apollo_Lookup_Dependencies_Per_Sibling_When_Sibling_Asset_Selections_Differ | Apollo Federation connector (other spec) |
| AbstractLookupFanoutPlanningTests.Plan_Should_Attribute_Nested_Apollo_Lookup_Dependencies_Per_Sibling_Without_Request_Grouping | Apollo Federation connector (other spec) |
| AbstractLookupFanoutPlanningTests.Plan_Should_Assign_Unique_Ids_Across_Nodes_And_Definitions | theory |
| DeferPlannerTests.Defer_SingleFragment_ProducesDeferredGroup | @defer, @stream or subscription |
| DeferPlannerTests.Defer_MultipleFragments_ProducesMultipleDeferredGroups | @defer, @stream or subscription |
| DeferPlannerTests.Defer_WithLabel_LabelIsPropagated | @defer, @stream or subscription |
| DeferPlannerTests.Defer_OperationHasIncrementalParts | @defer, @stream or subscription |
| DeferPlannerTests.Defer_NoDefer_NoDeferredGroups | no plan snapshot |
| DeferPlannerTests.Defer_ConditionalVariable_IfVariableRecorded | @defer, @stream or subscription |
| DeferPlannerTests.Defer_MainPlanStillExecutes | @defer, @stream or subscription |
| DeferPlannerTests.Defer_IfFalseLiteral_Should_ProduceNoDeferredGroups | @defer, @stream or subscription |
| DeferPlannerTests.Defer_IfTrueLiteral_Should_ProduceDeferredGroup | @defer, @stream or subscription |
| DeferPlannerTests.Defer_NestedDefer_Should_ProduceParentChildRelationship | @defer, @stream or subscription |
| DeferPlannerTests.Defer_WithIncludeDirective_Should_ProduceDeferredGroup | @defer, @stream or subscription |
| DeferPlannerTests.Plan_Should_Partition_Nested_Defer_With_Mixed_If_Conditions_Correctly | @defer, @stream or subscription |
| DeferPlannerTests.Defer_OnMutationResult_Should_ProduceDeferredGroup | @defer, @stream or subscription |
| DeferPlannerTests.Defer_RequirementReachableFromParent_Should_InjectIntoParentOp_When_SameSubgraph | @defer, @stream or subscription |
| DeferPlannerTests.Defer_MultipleDeferGroupsShareRequirement_Should_DeduplicateHoistedField | @defer, @stream or subscription |
| DeferPlannerTests.Defer_RequirementOnForeignSubgraph_Should_PlanParentScopeLookup | @defer, @stream or subscription |
| DeferPlannerTests.Defer_NestedDefer_InnerRequirement_Should_ResolveAgainstOuterDefer | @defer, @stream or subscription |
| DeferPlannerTests.Defer_NestedDefer_InnerAndOuterShareRequirement_Should_DeduplicateAtOuter | @defer, @stream or subscription |
| DeferPlannerTests.Defer_NestedDefer_InnerRequirement_OnForeignSubgraph_Should_PlanLookupInOuterScope | @defer, @stream or subscription |
| DeferPlannerTests.Defer_NestedDefer_InnerRequirement_Should_ResolveAtOuterScope_When_SameSubgraphAsOuterKey | @defer, @stream or subscription |
| DeferPlannerTests.Defer_NestedDefer_InnerRequirement_UnreachableFromOuter_Should_BubbleToRoot | no ComposeSchema(...) |
| DeferPlannerTests.Defer_UnsatisfiableRequirement_Should_ThrowPlannerError_When_NotReachableAnywhere | no ComposeSchema(...) |
| DeferPlannerTests.Defer_OnMutationRoot_Should_ThrowPlannerError_When_NoLookupAvailable | @defer, @stream or subscription |
| DeferPlannerTests.Defer_OnMutationResult_Should_ThrowPlannerError_When_DeferredFieldNeedsSecondMutationCall | @defer, @stream or subscription |
| DeferPlannerTests.Defer_NestedBelowMutationRootField_Should_ProduceKeyedLookup_When_AnchorHasLookup | @defer, @stream or subscription |
| DeferPlannerTests.MaxNodeId_Should_Match_Max_Node_Id_Of_AllNodes_When_Computed | @defer, @stream or subscription |
| DeferPlannerTests.IncrementalPlan_Ids_Should_Be_Positional_When_Plan_Built | @defer, @stream or subscription |
| DeferPlannerTests.Defer_SingleAnchor_Should_Keep_NodeRequirements_AllImported_Or_AllLocal | @defer, @stream or subscription |
| DeferPlannerTests.Defer_NestedDefer_Should_Keep_NodeRequirements_AllImported_Or_AllLocal | @defer, @stream or subscription |
| DeferPlannerTests.Defer_StepInternalPredecessor_Should_Keep_NodeRequirements_AllImported_Or_AllLocal | @defer, @stream or subscription |
| DeferPlannerTests.Defer_UnusedRootVariable_Should_Not_Be_Declared_On_IncrementalPlanOperation | @defer, @stream or subscription |
| DeferPlannerTests.Defer_VariableUsedInDeferredSelection_Should_Be_Declared_On_IncrementalPlanOperation | @defer, @stream or subscription |
| DeferPlannerTests.Defer_ListAnchor_Should_PlanKeyedLookup_When_MultipleRootFieldsPrecedeIt | @defer, @stream or subscription |
| DeferPlannerTests.Defer_ListAnchor_Should_PlanOneKeyedLookupPerField_When_FieldsSplitAcrossSameAndForeignSubgraph | @defer, @stream or subscription |
| DeferPlannerTests.Defer_ObjectAnchor_Should_KeepRootRefetch_When_AnchorTypeHasNoLookup | @defer, @stream or subscription |
| DeferPlannerTests.Defer_RootAnchor_Should_PlanRootFetch_When_DeferSitsAtOperationRoot | @defer, @stream or subscription |
| DeferPlannerTests.Defer_KeyOnlyField_Should_NotDefer_When_OnlyReachableLookupIsSelfCyclic | @defer, @stream or subscription |
| DeferPlannerTests.Defer_KeyOnlyField_Should_KeepTypename_When_ClientSelectsTypenameSibling | @defer, @stream or subscription |
| DeferPlannerTests.Defer_KeyOnlyField_Should_NotDefer_When_TypenameInsideDefer | @defer, @stream or subscription |
| DeferPlannerTests.Defer_FieldAlreadyRequiredByParent_Should_NotDefer | @defer, @stream or subscription |
| DeferPlannerTests.Defer_FieldAlreadyRequiredByParent_Should_NotDefer_When_TypenameInsideDefer | @defer, @stream or subscription |
| DeferPlannerTests.Defer_ProviderServesTwoRequirements_Should_KeepEdge_When_OnlyOneIsRerouted | @defer, @stream or subscription |
| EntityChainTests.Complex_Entity_Call_Nested_List_Key_Depends_On_Producing_Hop | schema from CreateComplexEntityCallWithListSchema() without ComposeSchema(...) |
| EventStreamPlannerTests.CreatePlan_Should_UseStandardDependents_When_EventStreamHasSingleMessageShape | no ComposeSchema(...) |
| EventStreamPlannerTests.CreatePlan_Should_ResolveNestedEntityFields_When_EventStreamReturnsWrapperType | ComposeSchema with options or shared schemas |
| EventStreamPlannerTests.FormatPlan_Should_WriteTopics_When_EventStreamHasSource | no ComposeSchema(...) |
| FusionBenchmarkTests.Simple_Query_With_Requirements | schema from CreateSchema() without ComposeSchema(...) |
| FusionBenchmarkTests.Complex_Query | schema from CreateSchema() without ComposeSchema(...) |
| FusionBenchmarkTests.Conditional_Redundancy_Query | schema from CreateSchema() without ComposeSchema(...) |
| GeneratedOperationNameTests.CreatePlan_Should_KeepTheShortHash_When_ItHoldsNameCharactersOnly | schema from CreateCompositeSchema() without ComposeSchema(...) |
| GeneratedOperationNameTests.CreatePlan_Should_ReplaceTheCharacter_When_TheShortHashHoldsANonNameCharacter | schema from CreateCompositeSchema() without ComposeSchema(...) |
| GeneratedOperationNameTests.CreatePlan_Should_KeepTheShortHash_When_TheOperationShortHashIsRead | schema from CreateCompositeSchema() without ComposeSchema(...) |
| InterfaceInheritanceLookupPlanningTests.Plan_Should_TargetAbstractTypeCondition_When_LookupReachedThroughInterfaceFragment_Issue10045 | ComposeSchema with options or shared schemas |
| InterfaceInheritanceLookupPlanningTests.Plan_Should_TargetConcreteTypeConditions_When_LookupReachedThroughConcreteFragments_Issue10045 | ComposeSchema with options or shared schemas |
| InterfaceLookupPlanningTests.Abstract_Customer_Interface_With_Id_Only_Is_Plannable | no plan snapshot |
| K6PlanTests.DeepNesting | ComposeSchema with options or shared schemas |
| OperationMergePolicyTests.Aggressive_Merges_Cross_Depth_Operations | PlanOperation without a raw string query |
| OperationMergePolicyTests.Conservative_Does_Not_Merge_Cross_Depth_Operations | PlanOperation without a raw string query |
| OperationMergePolicyTests.Balanced_Does_Not_Merge_Distant_Depth_Operations | PlanOperation without a raw string query |
| OperationMergePolicyTests.Balanced_Merges_Adjacent_Depth_Operations | PlanOperation without a raw string query |
| OperationMergePolicyTests.Aggressive_Same_Depth_Merges | PlanOperation without a raw string query |
| OperationMergePolicyTests.Conservative_Same_Depth_Merges | PlanOperation without a raw string query |
| OperationMergePolicyTests.Balanced_Same_Depth_Merges | PlanOperation without a raw string query |
| OperationMergePolicyTests.Merge_Should_Dedup_Into_Single_Batch_When_Forwarded_Literal_Is_MultiByte | PlanOperation without a raw string query |
| OperationMergePolicyTests.Balanced_Produces_More_Nodes_Than_Aggressive_For_Distant_Depths | several PlanOperation(...) calls |
| OperationMergePolicyTests.Cycle_Safety_Always_Enforced_In_All_Modes | PlanOperation without a raw string query |
| OperationMergePolicyTests.Default_MergePolicy_Is_Aggressive | no ComposeSchema(...) |
| OperationMergePolicyTests.Snapshot_Aggressive_K6 | ComposeSchema with options or shared schemas |
| OperationMergePolicyTests.Snapshot_Conservative_K6 | ComposeSchema with options or shared schemas |
| OperationMergePolicyTests.Snapshot_Balanced_K6 | ComposeSchema with options or shared schemas |
| OperationPlannerBatchingGroupIdTests.Plan_With_RequestGrouping_Disabled_Assigns_No_BatchingGroupIds | PlanOperation without a raw string query |
| OperationPlannerBatchingGroupIdTests.Plan_With_RequestGrouping_Enabled_Assigns_Deterministic_BatchingGroupIds | several PlanOperation(...) calls |
| OperationPlannerBatchingGroupIdTests.CreateBatchingGroupLookup_Dependent_Query_Nodes_Do_Not_Share_Group | no PlanOperation(...) |
| OperationPlannerBatchingGroupIdTests.CreateBatchingGroupLookup_Nodes_From_Different_Schemas_Do_Not_Share_Group | no PlanOperation(...) |
| OperationPlannerBatchingGroupIdTests.Plan_NonQuery_Operation_Nodes_Do_Not_Get_BatchingGroupId | @defer, @stream or subscription |
| OperationPlannerBatchingGroupIdTests.Serialization_Includes_BatchingGroupId_When_Present | PlanOperation without a raw string query |
| OperationPlannerBatchingGroupIdTests.Serialization_Omits_BatchingGroupId_When_Null | PlanOperation without a raw string query |
| OperationPlannerBatchingGroupIdTests.Snapshot_Plan_Shows_BatchingGroup_When_Group_Is_Created | PlanOperation without a raw string query |
| OperationPlannerCancellationTests.CreatePlan_Throws_When_CancellationToken_Is_Already_Canceled | schema from CreateCompositeSchema() without ComposeSchema(...) |
| OperationPlannerCancellationTests.CreatePlan_Throws_When_CancellationToken_Is_Canceled_During_Planning | schema from CreateCompositeSchema() without ComposeSchema(...) |
| OperationPlannerCostModelTests.PathCost_Defaults_Prefer_ModerateFanout_To_SequentialChain | no ComposeSchema(...) |
| OperationPlannerCostModelTests.PathCost_Defaults_Penalize_ExcessiveFanout | no ComposeSchema(...) |
| OperationPlannerCostModelTests.Constructors_Wire_Default_And_Custom_Options | schema from CreateCompositeSchema() without ComposeSchema(...) |
| OperationPlannerCostModelTests.RemainingCost_Projects_RemainingDepth_For_EqualOperationFloor | no ComposeSchema(...) |
| OperationPlannerCostModelTests.RemainingCost_Projects_ExcessFanout_For_EqualOperationFloor | no ComposeSchema(...) |
| OperationPlannerGuardrailTests.CreatePlan_Throws_When_MaxExpandedNodes_Guardrail_Is_Exceeded | schema from CreateCompositeSchema() without ComposeSchema(...) |
| OperationPlannerGuardrailTests.CreatePlan_Throws_When_MaxQueueSize_Guardrail_Is_Exceeded | no PlanOperation(...) |
| OperationPlannerGuardrailTests.CreatePlan_Throws_When_MaxPlanningTime_Guardrail_Is_Exceeded | schema from CreateCompositeSchema() without ComposeSchema(...) |
| OperationPlannerGuardrailTests.CreatePlan_Throws_When_MaxGeneratedOptions_Guardrail_Is_Exceeded | no PlanOperation(...) |
| OperationPlannerSelectionPathTests.ContainsSelectionsAtPath_Should_NotMatch_When_RuntimeTypeIsSibling | no ComposeSchema(...) |
| OperationPlannerSelectionPathTests.ContainsSelectionsAtPath_Should_NotMatch_When_RuntimeTypeIsConditional | no ComposeSchema(...) |
| OperationPlannerTests.Plan_Simple_Operation_1_Source_Schema | schema from CreateCompositeSchema() without ComposeSchema(...) |
| OperationPlannerTests.Plan_Simple_Operation_2_Source_Schema | no ComposeSchema(...) |
| OperationPlannerTests.Plan_Simple_Operation_3_Source_Schema | no ComposeSchema(...) |
| PlannerBehaviorTests.CreatePlan_Should_Succeed_When_Inlined_Fragment_Expansion_Exceeds_Parser_Field_Limit | ComposeSchema with options or shared schemas |
| PlannerBehaviorTests.CreatePlan_Should_Succeed_When_Each_Fragment_Is_Spread_Once | ComposeSchema with options or shared schemas |
| PlannerEventSourceTests.PlannerEventSource_Emits_Start_And_Stop_With_Perf_Metrics | schema from CreateCompositeSchema() without ComposeSchema(...) |
| PlannerEventSourceTests.PlannerEventSource_Emits_Error_When_Planning_Fails | no PlanOperation(...) |
| PlannerEventSourceTests.PlannerEventSource_Can_Aggregate_Perf_Metrics_Across_Plans | schema from CreateCompositeSchema() without ComposeSchema(...) |
| PlannerEventSourceTests.PlannerEventSource_Emits_Guardrail_Event_When_Guardrail_Is_Exceeded | schema from CreateCompositeSchema() without ComposeSchema(...) |
| RequirementCrossEntityTests.Plan_Should_Resolve_Subtotal_When_Require_Traverses_Connection_With_Nested_Require | no ComposeSchema(...) |
| RequirementCrossEntityTests.Plan_Should_Resolve_Subtotal_When_Require_Traverses_Connection_With_Input_Object_List_Form | no ComposeSchema(...) |
| RequirementCrossEntityTests.Plan_Should_Resolve_Subtotal_When_Connection_Leaf_Has_No_Nested_Require | no ComposeSchema(...) |
| RequirementCrossEntityTests.Plan_Should_Resolve_Subtotal_When_Requiring_Field_Is_On_Other_Schema_Than_Connection | no ComposeSchema(...) |
| RequirementReentrancyTests.Plan_Should_Reenter_Catalog_When_InnerProductCategory_Crosses_RequireBoundary | no plan snapshot |
| RequirementReentrancyTests.Plan_Should_Keep_Require_Subtree_In_Owning_Schema_When_Entry_Schema_Declares_Nested_Lookup | no plan snapshot |
| RequirementTests.Requirement_SelectionMap_Object | ComposeSchema with options or shared schemas |
| RequirementTests.Requirement_SelectionMap_Object_Shop | ComposeSchema with options or shared schemas |
| RequirementTests.Plan_Complex_Operation | ComposeSchema with options or shared schemas |
| RequirementTests.Requirement_Directive_Leaks_Into_SourceSchema_Request_Shop | ComposeSchema with options or shared schemas |
| SelectionSetByTypePartitionerTests.Only_Selections_On_Concrete_Type | ComposeSchema with options or shared schemas |
| SelectionSetByTypePartitionerTests.Only_Shared_Selections | ComposeSchema with options or shared schemas |
| SelectionSetByTypePartitionerTests.Selections_On_Shared_And_Concrete_Type | ComposeSchema with options or shared schemas |
| SelectionSetByTypePartitionerTests.Selections_On_Shared_And_Concrete_Type_With_Conditions | ComposeSchema with options or shared schemas |
| SelectionSetByTypePartitionerTests.Selections_On_Interface | ComposeSchema with options or shared schemas |
| SelectionSetByTypePartitionerTests.Selections_On_Interface_Skips_Implementors_That_Are_Not_Possible_Types | ComposeSchema with options or shared schemas |
| SelectionSetByTypePartitionerTests.Concrete_Type_Selections_Within_Interface | ComposeSchema with options or shared schemas |
| SelectionSetByTypePartitionerTests.Interface_Selections_Within_Concrete_Type | ComposeSchema with options or shared schemas |
| SelectionSetByTypePartitionerTests.Nested_Interface_Selections_Skip_Implementors_Outside_Outer_Interface | ComposeSchema with options or shared schemas |
| SelectionSetByTypePartitionerTests.Spread_On_Type_Of_SelectionSet_Is_Part_Of_Shared_Selections | ComposeSchema with options or shared schemas |
| SelectionSetByTypePartitionerTests.Spread_With_TypeCondition_On_Type_Of_SelectionSet_Is_Part_Of_Shared_Selections | ComposeSchema with options or shared schemas |
| SelectionSetByTypePartitionerTests.Conditional_Concrete_Type_Selections | ComposeSchema with options or shared schemas |
| SelectionSetByTypePartitionerTests.Conditional_Shared_Selections | ComposeSchema with options or shared schemas |
| SelectionSetByTypePartitionerTests.Conditional_SelectionSet_Root | ComposeSchema with options or shared schemas |
| SelectionSetIndexBuilderTests.OnMerge_Should_AdvanceNextId_When_FieldSelectionSetsAreUnregistered | no ComposeSchema(...) |
| SelectionSetIndexBuilderTests.OnMerge_Should_AdvanceNextId_When_SelectionSetsAreUnregistered | no ComposeSchema(...) |
| SelectionSetPartitionerTests.Extract_Name_Enqueue_Reviews | no ComposeSchema(...) |
| SelectionSetPartitionerTests.Extract_Name_Enqueue_Reviews_Enqueue_Name | no ComposeSchema(...) |
| ShareableFieldRuntimeTypeRoutingPlanningTests.Plan_Should_IntersectRuntimeTypesAcrossShareableRootProviders | no ComposeSchema(...) |
| ShareableFieldRuntimeTypeRoutingPlanningTests.Plan_Should_ExcludeSourceExternalProvider | no ComposeSchema(...) |
| ShareableFieldRuntimeTypeRoutingPlanningTests.Plan_Should_UseSourceLocalRuntimeTypesForMixedConnectors | no ComposeSchema(...) |
| ShareableFieldRuntimeTypeRoutingPlanningTests.Plan_Should_ResetProviderScopeForProvidedField | no ComposeSchema(...) |
| ShareableFieldRuntimeTypeRoutingPlanningTests.Plan_Should_HonorConcreteSourceTypeNarrowing | no ComposeSchema(...) |
| ShareableFieldRuntimeTypeRoutingPlanningTests.Plan_Should_TreatInterfaceObjectProviderAsWildcard | no ComposeSchema(...) |
| ShareableFieldRuntimeTypeRoutingPlanningTests.Plan_Should_ApplyPolicyDifferentialOnKeyedShareablePath | no ComposeSchema(...) |
| ShareableFieldRuntimeTypeRoutingPlanningTests.Plan_Should_NarrowProviderScopeAcrossProviderSpecificField | no ComposeSchema(...) |
| ShopPlanningTests.Medium_Query_With_Aliases | ComposeSchema with options or shared schemas |
| SourceFieldTypeMismatchRewriterTests.Rewrite_Should_AliasField_When_SourceLeafTypeNamesDiffer | no ComposeSchema(...) |
| SourceFieldTypeMismatchRewriterTests.RewriteDynamic_Should_AliasField_When_AnySourceLeafTypeNamesDiffer | no ComposeSchema(...) |
| SupertypeNarrowingPlanningTests.Plan_Should_SpillWholeFieldToCoveringSchema_When_UnionNarrowingCannotCoverRequestedMember | schema from CreateFeaturedItemSchema() without ComposeSchema(...) |
| SupertypeNarrowingPlanningTests.Plan_Should_AllowNarrowingSource_When_UnionNarrowingCoversOnlyRequestedMember | schema from CreateFeaturedItemSchemaWithProductNameOnlyOnB() without ComposeSchema(...) |
| SupertypeNarrowingPlanningTests.Plan_Should_SpillNestedFieldToCoveringSchema_When_UnionNarrowingCannotCoverRequestedMember | schema from CreateCategoryFeaturedItemSchema() without ComposeSchema(...) |
| SupertypeNarrowingPlanningTests.Plan_Should_AllowNestedNarrowingSource_When_UnionNarrowingCoversOnlyRequestedMember | schema from CreateCategoryFeaturedItemSchemaWithProductNameOnlyOnB() without ComposeSchema(...) |
| SupertypeNarrowingPlanningTests.Plan_Should_SpillRootFieldToCoveringSchema_When_NarrowedFieldHasRequirementsAndFragmentIsUncovered | schema from CreateFeaturedItemSchemaWithRequirements() without ComposeSchema(...) |
| SupertypeNarrowingPlanningTests.Plan_Should_SpillNestedFieldToCoveringSchema_When_NarrowedFieldHasRequirementsAndFragmentIsUncovered | schema from CreateCategoryFeaturedItemSchemaWithRequirements() without ComposeSchema(...) |
| SupertypeNarrowingPlanningTests.Plan_Should_AllowNarrowingSource_When_InterfaceFragmentsAreCoveredBySourceObject | no ComposeSchema(...) |
| SupertypeNarrowingPlanningTests.Plan_Should_SpillWholeFieldToCoveringSchema_When_InterfaceFragmentIsNotCoveredBySourceObject | no ComposeSchema(...) |
| SupertypeNarrowingPlanningTests.Partition_Should_ThrowNotSupportedException_When_SourceNarrowsToAbstractType | schema from CreateAbstractNarrowingSchema() without ComposeSchema(...) |
| SupertypeNarrowingPlanningTests.Plan_Should_SpillWholeFieldToCoveringSchema_When_UncoveredFragmentHasConditionalDirective | schema from CreateFeaturedItemSchema() without ComposeSchema(...) |
| SupertypeNarrowingPlanningTests.Plan_Should_SpillWholeFieldToCoveringSchema_When_UncoveredFragmentIsNestedInConditionlessFragment | schema from CreateFeaturedItemSchema() without ComposeSchema(...) |
| SupertypeNarrowingPlanningTests.Partition_Should_SpillNarrowedFieldBeforeEnqueuingRequirements_When_FragmentIsUncovered | no ComposeSchema(...) |
| SupertypeNarrowingPlanningTests.Partition_Should_EnqueueRequirements_When_RootNarrowedFieldCoversRequestedFragments | schema from CreateFeaturedItemSchemaWithRequirementsAndProductNameOnlyOnB() without ComposeSchema(...) |
| SupertypeNarrowingPlanningTests.Partition_Should_SpillNestedNarrowedFieldBeforeEnqueuingRequirements_When_FragmentIsUncovered | schema from CreateCategoryFeaturedItemSchemaWithRequirements() without ComposeSchema(...) |
| SupertypeNarrowingPlanningTests.Partition_Should_EnqueueRequirements_When_NestedNarrowedFieldCoversRequestedFragments | schema from CreateCategoryFeaturedItemSchemaWithRequirementsAndProductNameOnlyOnB() without ComposeSchema(...) |
| SupertypeNarrowingPlanningTests.Partition_Should_Expand_InterfaceFragment_When_SourceMembershipIsNarrower | schema from CreateDistributedInterfaceMembershipSchema() without ComposeSchema(...) |
| SupertypeNarrowingPlanningTests.Partition_Should_RouteNestedInterfaceFragment_When_ParentWasExpandedToConcreteTypes | schema from CreateDistributedInterfaceMembershipSchema() without ComposeSchema(...) |
| SupertypeNarrowingPlanningTests.Partition_Should_CloneNestedSelectionSets_When_ParentExpandsToConcreteTypes | schema from CreateDistributedInterfaceMembershipSchema() without ComposeSchema(...) |
| SupertypeNarrowingPlanningTests.Plan_Should_FetchNestedInterfaceField_When_SyntheticBranchIsOwnedByOtherSource | schema from CreateDistributedInterfaceMembershipSchema() without ComposeSchema(...) |
| SupertypeNarrowingPlanningTests.Partition_Should_NotExpandInterfaceFragment_When_SourceFieldNarrowsRuntimeType | schema from CreateNarrowedDistributedInterfaceMembershipSchema() without ComposeSchema(...) |
| SupertypeNarrowingPlanningTests.Partition_Should_PruneConcreteFragment_When_SourceParentCannotProduceType | schema from CreateDistributedInterfaceMembershipSchema() without ComposeSchema(...) |
