/**
 *  Copyright 2012-2015 Gunnar Morling (http://www.gunnarmorling.de/)
 *  and/or other contributors as indicated by the @authors tag. See the
 *  copyright.txt file in the distribution for a full listing of all
 *  contributors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.mapstruct.ap.model.source;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.SimpleTypeVisitor6;
import javax.lang.model.util.Types;

import org.mapstruct.ap.model.common.Type;

import static org.mapstruct.ap.util.SpecificCompilerWorkarounds.isSubType;

/**
 * SourceMethodMatcher $8.4 of the JavaLanguage specification describes a method body as such:
 *
 * <pre>
 * SourceMethodDeclaration: SourceMethodHeader SourceMethodBody
 * SourceMethodHeader: SourceMethodModifiers TypeParameters Result SourceMethodDeclarator Throws
 * SourceMethodDeclarator: Identifier ( FormalParameterList )
 *
 * example &lt;T extends String &amp; Serializable&gt;  T   getResult(? extends T) throws Exception
 *         \-------------------------------/ \-/            \---------/
 *               TypeParameters             Result        ParameterList
 * </pre>
 *
 * Matches a given method with given ParameterList and Result type obeying the constraints in the TypeParameters block.
 * <p>
 * For more info on java-generics: http://www.javacodegeeks.com/2011/04/java-generics-quick-tutorial.html
 * http://www.angelikalanger.com/GenericsFAQ/FAQSections/ParameterizedTypes.html
 * <p>
 * The following situations is not supported / tested:
 * <ol>
 * <li>Multiple bounds were the bound itself is again a generic type.</li>
 * </ol>
 *
 * @author Sjaak Derksen
 */
public class MethodMatcher {

    private final SourceMethod candidateMethod;
    private final Types typeUtils;

    MethodMatcher(Types typeUtils, SourceMethod candidateMethod) {
        this.typeUtils = typeUtils;
        this.candidateMethod = candidateMethod;
    }

    /**
     * Whether the given source and target types are matched by this matcher's candidate method.
     *
     * @param sourceTypes the source types
     * @param targetType the target type
     *
     * @return {@code true} when both, source type and target types match the signature of this matcher's method;
     *         {@code false} otherwise.
     */
    boolean matches(List<Type> sourceTypes, Type targetType) {
        // check & collect generic types.
        List<? extends VariableElement> candidateParameters = candidateMethod.getExecutable().getParameters();

        if ( candidateParameters.size() != sourceTypes.size() ) {
            return false;
        }

        Map<TypeVariable, TypeMirror> genericTypesMap = new HashMap<TypeVariable, TypeMirror>();

        int i = 0;
        for ( VariableElement candidateParameter : candidateParameters ) {
            TypeMatcher parameterMatcher = new TypeMatcher( Assignability.VISITED_ASSIGNABLE_FROM, genericTypesMap );
            Type sourceType = sourceTypes.get( i++ );
            if ( !parameterMatcher.visit( candidateParameter.asType(), sourceType.getTypeMirror() ) ) {
                if (sourceType.isPrimitive() ) {
                    // the candidate source is primitive, so promote to its boxed type and check again (autobox)
                    TypeMirror boxedType = typeUtils.boxedClass( (PrimitiveType) sourceType.getTypeMirror() ).asType();
                    if ( !parameterMatcher.visit( candidateParameter.asType(), boxedType ) ) {
                        return false;
                    }
                }
                else {
                    // NOTE: unboxing is deliberately not considered here. This should be handled via type-conversion
                    // (for NPE safety).
                    return false;
                }
            }
        }

        // check return type
        TypeMirror candidateReturnType = candidateMethod.getExecutable().getReturnType();
        TypeMatcher returnTypeMatcher = new TypeMatcher( Assignability.VISITED_ASSIGNABLE_TO, genericTypesMap );

        if ( !returnTypeMatcher.visit( candidateReturnType, targetType.getTypeMirror() ) ) {
            if ( targetType.isPrimitive() ) {
                TypeMirror boxedType = typeUtils.boxedClass( (PrimitiveType) targetType.getTypeMirror() ).asType();
                TypeMatcher boxedReturnTypeMatcher =
                    new TypeMatcher( Assignability.VISITED_ASSIGNABLE_TO, genericTypesMap );

                if ( !boxedReturnTypeMatcher.visit( candidateReturnType, boxedType ) ) {
                    return false;
                }
            }
            else if ( candidateReturnType.getKind().isPrimitive() ) {
                TypeMirror boxedCandidateReturnType =
                    typeUtils.boxedClass( (PrimitiveType) candidateReturnType ).asType();
                TypeMatcher boxedReturnTypeMatcher =
                    new TypeMatcher( Assignability.VISITED_ASSIGNABLE_TO, genericTypesMap );

                if ( !boxedReturnTypeMatcher.visit( boxedCandidateReturnType, targetType.getTypeMirror() ) ) {
                    return false;
                }

            }
            else {
                return false;
            }
        }

        // check if all type parameters are indeed mapped
        if ( candidateMethod.getExecutable().getTypeParameters().size() != genericTypesMap.size() ) {
            return false;
        }

        // check if all entries are in the bounds
        for ( Map.Entry<TypeVariable, TypeMirror> entry : genericTypesMap.entrySet() ) {
            if ( !isWithinBounds( entry.getValue(), getTypeParamFromCandidate( entry.getKey() ) ) ) {
                // checks if the found Type is in bounds of the TypeParameters bounds.
                return false;
            }
        }
        return true;
    }

    private enum Assignability {
        VISITED_ASSIGNABLE_FROM, VISITED_ASSIGNABLE_TO
    }

    private class TypeMatcher extends SimpleTypeVisitor6<Boolean, TypeMirror> {
        private final Assignability assignability;
        private final Map<TypeVariable, TypeMirror> genericTypesMap;

        public TypeMatcher(Assignability assignability, Map<TypeVariable, TypeMirror> genericTypesMap) {
            super( Boolean.FALSE ); // default value
            this.assignability = assignability;
            this.genericTypesMap = genericTypesMap;
        }

        @Override
        public Boolean visitPrimitive(PrimitiveType t, TypeMirror p) {
            return typeUtils.isSameType( t, p );
        }

        @Override
        public Boolean visitArray(ArrayType t, TypeMirror p) {

            if ( p.getKind().equals( TypeKind.ARRAY ) ) {
                return t.getComponentType().accept( this, ( (ArrayType) p ).getComponentType() );
            }
            else {
                return Boolean.FALSE;
            }
        }

        @Override
        public Boolean visitDeclared(DeclaredType t, TypeMirror p) {
            // its a match when: 1) same kind of type, name is equals, nr of type args are the same
            // (type args are checked later).
            if ( p.getKind() == TypeKind.DECLARED ) {
                DeclaredType t1 = (DeclaredType) p;
                if ( assignabilityMatches( t, t1 )
                    && t.getTypeArguments().size() == t1.getTypeArguments().size() ) {
                    for ( int i = 0; i < t.getTypeArguments().size(); i++ ) {
                        if ( !t.getTypeArguments().get( i ).accept( this, t1.getTypeArguments().get( i ) ) ) {
                            return Boolean.FALSE;
                        }
                    }
                    return Boolean.TRUE;
                }
                else {
                    return Boolean.FALSE;
                }
            }
            else {
                return Boolean.FALSE;
            }
        }

        private boolean assignabilityMatches(DeclaredType visited, DeclaredType param) {
            if ( assignability == Assignability.VISITED_ASSIGNABLE_TO ) {
                return typeUtils.isAssignable( toRawType( visited ), toRawType( param ) );
            }
            else {
                return typeUtils.isAssignable( toRawType( param ), toRawType( visited ) );
            }
        }

        private DeclaredType toRawType(DeclaredType t) {
            return typeUtils.getDeclaredType( (TypeElement) t.asElement() );
        }

        @Override
        public Boolean visitTypeVariable(TypeVariable t, TypeMirror p) {
            if ( genericTypesMap.containsKey( t ) ) {
                // when already found, the same mapping should apply
                TypeMirror p1 = genericTypesMap.get( t );
                return typeUtils.isSameType( p, p1 );
            }
            else {
                // check if types are in bound
                TypeMirror lowerBound = t.getLowerBound();
                TypeMirror upperBound = t.getUpperBound();
                if ( ( isNullType( lowerBound ) || isSubType( typeUtils, lowerBound, p ) )
                    && ( isNullType( upperBound ) || isSubType( typeUtils, p, upperBound ) ) ) {
                    genericTypesMap.put( t, p );
                    return Boolean.TRUE;
                }
                else {
                    return Boolean.FALSE;
                }
            }
        }

        private boolean isNullType(TypeMirror type) {
            return type == null || type.getKind() == TypeKind.NULL;
        }

        @Override
        public Boolean visitWildcard(WildcardType t, TypeMirror p) {

            // check extends bound
            TypeMirror extendsBound = t.getExtendsBound();
            if ( !isNullType( extendsBound ) ) {
                switch ( extendsBound.getKind() ) {
                    case DECLARED:
                        // for example method: String method(? extends String)
                        // isSubType checks range [subtype, type], e.g. isSubtype [Object, String]==true
                        return isSubType( typeUtils, p, extendsBound );

                    case TYPEVAR:
                        // for example method: <T extends String & Serializable> T method(? extends T)
                        // this can be done the directly by checking: ? extends String & Serializable
                        // this checks the part? <T extends String & Serializable>
                        return isWithinBounds( p, getTypeParamFromCandidate( extendsBound ) );

                    default:
                        // does this situation occur?
                        return Boolean.FALSE;
                }
            }

            // check super bound
            TypeMirror superBound = t.getSuperBound();
            if ( !isNullType( superBound ) ) {
                switch ( superBound.getKind() ) {
                    case DECLARED:
                        // for example method: String method(? super String)
                        // to check super type, we can simply reverse the argument, but that would initially yield
                        // a result: <type, superType] (so type not included) so we need to check sameType also.
                        return isSubType( typeUtils, superBound, p ) || typeUtils.isSameType( p, superBound );

                    case TYPEVAR:

                        TypeParameterElement typeParameter = getTypeParamFromCandidate( superBound );
                        // for example method: <T extends String & Serializable> T method(? super T)
                        if ( !isWithinBounds( p, typeParameter ) ) {
                            // this checks the part? <T extends String & Serializable>
                            return Boolean.FALSE;
                        }
                        // now, it becomes a bit more hairy. We have the relation (? super T). From T we know that
                        // it is a subclass of String & Serializable. However, The Java Language Secification,
                        // Chapter 4.4, states that a bound is either: 'A type variable-', 'A class-' or 'An
                        // interface-' type followed by further interface types. So we must compare with the first
                        // argument in the Expression String & Serializable & ..., so, in this case String.
                        // to check super type, we can simply reverse the argument, but that would initially yield
                        // a result: <type, superType] (so type not included) so we need to check sameType also.
                        TypeMirror superBoundAsDeclared = typeParameter.getBounds().get( 0 );
                        return ( isSubType( typeUtils, superBoundAsDeclared, p ) || typeUtils.isSameType(
                            p,
                            superBoundAsDeclared ) );
                    default:
                        // does this situation occur?
                        return Boolean.FALSE;
                }
            }
            return Boolean.TRUE;
        }

    }

    /**
     * Looks through the list of type parameters of the candidate method for a match
     *
     * @param t type parameter to match
     *
     * @return matching type parameter
     */
    private TypeParameterElement getTypeParamFromCandidate(TypeMirror t) {
        for ( TypeParameterElement candidateTypeParam : candidateMethod.getExecutable().getTypeParameters() ) {
            if ( candidateTypeParam.asType().equals( t ) ) {
                return candidateTypeParam;
            }
        }
        return null;
    }

    /**
     * checks whether a type t is in bounds of the typeParameter tpe
     *
     * @param t
     * @param tpe
     *
     * @return true if within bounds
     */
    private boolean isWithinBounds(TypeMirror t, TypeParameterElement tpe) {
        List<? extends TypeMirror> bounds = tpe.getBounds();
        if ( t != null && bounds != null ) {
            for ( TypeMirror bound : bounds ) {
                if ( !( bound.getKind() == TypeKind.DECLARED && isSubType( typeUtils, t, bound ) ) ) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }
}

