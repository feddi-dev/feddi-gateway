grammar FSM;

@header {
    package dev.feddi.federation.engine.parser.antlr;
}

@lexer::members {
}

// ============================================================================
// FieldSelectionSet - used by @key and @provides directives
// Supports GraphQL-like selection set syntax: "id name", "author { id name }"
// Also supports inline fragments: "... on Book { title }"
// ============================================================================

fieldSelectionSet: selectionItem+;

selectionItem: fieldSelection | inlineFragment;

fieldSelection: fieldName selectionSet?;

inlineFragment: '...' 'on' typeName selectionSet;

selectionSet: '{' selectionItem+ '}';

// ============================================================================
// FieldSelectionMap (SelectedValue) - used by @is and @require directives
// Supports paths, type conditions, and pipe-separated alternatives
// ============================================================================

selectedValue: '|'? selectedValueEntry ('|' selectedValueEntry)*;

selectedValueEntry: path | path '.' selectedObjectValue | path selectedListValue | selectedObjectValue;

selectedListValue: '[' selectedValue ']' | '[' selectedListValue ']';

selectedObjectValue: '{' selectedObjectField+ '}';

selectedObjectField: name ':' selectedValue | name;

path: '<' typeName '>' '.' pathSegment | pathSegment;

pathSegment: fieldName | fieldName '.' pathSegment | fieldName '<' typeName '>' '.' pathSegment;

// ============================================================================
// Common rules
// ============================================================================

fieldName: name;
typeName: name;
name: NAME;
NAME: [_A-Za-z][_0-9A-Za-z]*;

LF: [\n] -> channel(3);
CR: [\r] -> channel(3);
LineTerminator: [\u2028\u2029] -> channel(3);

Space: [\u0020] -> channel(3);
Tab: [\u0009] -> channel(3);
Comma: ',' -> channel(3);
UnicodeBOM: [\ufeff] -> channel(3);
