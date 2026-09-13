package io.dagger.codegen.introspection;

import com.palantir.javapoet.ClassName;

/**
 * Where every Java class a generated package refers to lives.
 *
 * <p>Generated code names three kinds of class: a schema type, a hand-written runtime class ({@code
 * QueryBuilder}, {@code Arguments}, ...), and itself. Every visitor used to name them by simple
 * name, which is only correct while everything lands in one package. Routing them through a
 * registry is the seam a second package needs.
 */
public final class TypeRegistry {

  private final String targetPackage;
  private final String corePackage;

  private TypeRegistry(String targetPackage, String corePackage) {
    this.targetPackage = targetPackage;
    this.corePackage = corePackage;
  }

  /** Everything in one package. */
  public static TypeRegistry singlePackage(String pkg) {
    return new TypeRegistry(pkg, pkg);
  }

  /** The package this registry emits into. */
  public String targetPackage() {
    return targetPackage;
  }

  /**
   * The Java class generated for a GraphQL type. {@code Query} is {@code Client}, and the builtin
   * scalars are their {@code java.lang} counterparts.
   */
  public ClassName forType(String graphqlName) {
    switch (graphqlName) {
      case "String":
        return ClassName.get(String.class);
      case "Boolean":
        return ClassName.get(Boolean.class);
      case "Int":
        return ClassName.get(Integer.class);
      case "Float":
        return ClassName.get(Float.class);
      default:
        return ClassName.get(corePackage, Helpers.formatName(graphqlName));
    }
  }

  /** The query-builder implementation generated next to a GraphQL interface. */
  public ClassName forInterfaceClient(String graphqlName) {
    ClassName iface = forType(graphqlName);
    return iface.peerClass(iface.simpleName() + "Client");
  }

  /** A hand-written runtime class. */
  public ClassName runtime(String simpleName) {
    return ClassName.get(corePackage, simpleName);
  }

  /** A hand-written runtime class in a subpackage of the runtime. */
  public ClassName runtime(String subpackage, String simpleName) {
    return ClassName.get(corePackage + "." + subpackage, simpleName);
  }
}
