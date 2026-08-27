package io.dagger.codegen.introspection;

import com.palantir.javapoet.ClassName;
import java.util.Set;

/**
 * Where every Java class a generated package refers to lives.
 *
 * <p>Generated code names three kinds of classes: schema types, which live in the core package or —
 * when a module owns them — in that module's client package; the hand-written runtime ({@code
 * QueryBuilder}, {@code Arguments}, ...); and itself. Resolving all of them here, rather than by
 * simple name, is what lets one generator emit into more than one package.
 */
public final class TypeRegistry {

  private final String targetPackage;
  private final String corePackage;
  private final String clientPackage;
  private final String runtimePackage;
  private final Set<String> ownedTypeNames;

  private TypeRegistry(
      String targetPackage,
      String corePackage,
      String clientPackage,
      String runtimePackage,
      Set<String> ownedTypeNames) {
    this.targetPackage = targetPackage;
    this.corePackage = corePackage;
    this.clientPackage = clientPackage;
    this.runtimePackage = runtimePackage;
    this.ownedTypeNames = Set.copyOf(ownedTypeNames);
  }

  /** Emitting the core package, with the runtime elsewhere. */
  public static TypeRegistry core(String corePackage, String runtimePackage) {
    return new TypeRegistry(corePackage, corePackage, corePackage, runtimePackage, Set.of());
  }

  /** Emitting one module's client package; every type it does not own resolves to core. */
  public static TypeRegistry client(
      String clientPackage, String corePackage, String runtimePackage, Set<String> ownedTypeNames) {
    return new TypeRegistry(
        clientPackage, corePackage, clientPackage, runtimePackage, ownedTypeNames);
  }

  /** The package this registry emits into. */
  public String targetPackage() {
    return targetPackage;
  }

  /**
   * The Java class generated for a GraphQL type. {@code Query} is {@code Core}; the builtin scalars
   * are their {@code java.lang} counterparts; a module-owned type is in its client package;
   * everything else is core.
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
        String pkg = ownedTypeNames.contains(graphqlName) ? clientPackage : corePackage;
        return ClassName.get(pkg, Helpers.formatName(graphqlName));
    }
  }

  /** The query-builder implementation generated next to a GraphQL interface. */
  public ClassName forInterfaceClient(String graphqlName) {
    ClassName iface = forType(graphqlName);
    return iface.peerClass(iface.simpleName() + "Client");
  }

  /** A hand-written runtime class. */
  public ClassName runtime(String simpleName) {
    return ClassName.get(runtimePackage, simpleName);
  }

  /** A hand-written runtime class in a runtime subpackage. */
  public ClassName runtime(String subpackage, String simpleName) {
    return ClassName.get(runtimePackage + "." + subpackage, simpleName);
  }
}
