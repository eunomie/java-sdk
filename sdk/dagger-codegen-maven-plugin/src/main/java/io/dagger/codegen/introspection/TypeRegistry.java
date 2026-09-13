package io.dagger.codegen.introspection;

import com.palantir.javapoet.ClassName;
import java.util.Map;

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
  private final Map<String, String> packageByTypeName;

  private TypeRegistry(
      String targetPackage, String corePackage, Map<String, String> packageByTypeName) {
    this.targetPackage = targetPackage;
    this.corePackage = corePackage;
    this.packageByTypeName = packageByTypeName;
  }

  /** Everything in one package. */
  public static TypeRegistry singlePackage(String pkg) {
    return new TypeRegistry(pkg, pkg, Map.of());
  }

  /**
   * Core in one package, and every type a module owns in that module's own package.
   *
   * <p>Built once for a whole plan, so a module's package can name a core type and core can name a
   * module's type without either knowing where the other landed.
   */
  public static TypeRegistry acrossPackages(
      String corePackage, Map<String, String> packageByTypeName) {
    return new TypeRegistry(corePackage, corePackage, Map.copyOf(packageByTypeName));
  }

  /** The same resolution, writing into a different package. */
  public TypeRegistry emittingInto(String pkg) {
    return new TypeRegistry(pkg, corePackage, packageByTypeName);
  }

  /** The package this registry emits into. */
  public String targetPackage() {
    return targetPackage;
  }

  /**
   * The Java class generated for a GraphQL type. {@code Query} is {@code Client}, the builtin
   * scalars are their {@code java.lang} counterparts, and a type a module owns is in that module's
   * package.
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
        return ClassName.get(
            packageByTypeName.getOrDefault(graphqlName, corePackage),
            Helpers.formatName(graphqlName));
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
