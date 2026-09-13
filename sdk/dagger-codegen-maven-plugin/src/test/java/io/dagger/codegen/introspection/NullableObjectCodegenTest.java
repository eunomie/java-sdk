package io.dagger.codegen.introspection;

import static org.assertj.core.api.Assertions.assertThat;

import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.TypeSpec;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NullableObjectCodegenTest {

  @TempDir Path compilationOutputDirectory;

  @Test
  void belowTheGateNullableObjectFieldsKeepTheLazyShape() throws Exception {
    String generated = generateInterface(interfaceWithOptionalObjectField(), "v1.0.0-beta.9");

    assertThat(generated).contains("Directory child();").doesNotContain("DaggerQueryException");
  }

  @Test
  void atTheGateNullableObjectFieldsReturnOptionalAndCanFail() throws Exception {
    String generated = generateInterface(interfaceWithOptionalObjectField(), "v1.0.0-beta.10");

    assertThat(generated).contains("Optional<Directory> child()").contains("DaggerQueryException");
  }

  @Test
  void narrowingAnInterfacesNullableObjectFieldCompiles() throws Exception {
    Type owner = type("Owner", TypeKind.INTERFACE);
    owner.setFields(List.of(field("pet", typeRef(TypeKind.INTERFACE, "Animal"), owner)));

    Type kennel = type("Kennel", TypeKind.OBJECT);
    kennel.setInterfaces(List.of(typeRef(TypeKind.INTERFACE, "Owner")));
    kennel.setFields(List.of(field("pet", typeRef(TypeKind.OBJECT, "Dog"), kennel)));

    assertCompiles(sources(owner, kennel));
  }

  /**
   * An object may declare a field non-null where the interface it implements declares it nullable.
   * The interface then returns Optional while the object stays lazy, and the two have to remain
   * compatible.
   */
  @Test
  void implementingANullableInterfaceFieldWithANonNullOneCompiles() throws Exception {
    Type owner = type("Owner", TypeKind.INTERFACE);
    owner.setFields(List.of(field("pet", typeRef(TypeKind.INTERFACE, "Animal"), owner)));

    Type kennel = type("Kennel", TypeKind.OBJECT);
    kennel.setInterfaces(List.of(typeRef(TypeKind.INTERFACE, "Owner")));
    kennel.setFields(List.of(field("pet", nonNull(typeRef(TypeKind.OBJECT, "Dog")), kennel)));

    assertCompiles(sources(owner, kennel));
  }

  /**
   * An interface may itself narrow the nullable field of the interface it implements, and GraphQL
   * requires the object to declare the whole hierarchy. Every generated method in the chain — the
   * two interfaces, their clients and the object — has to agree on Optional.
   */
  @Test
  void narrowingThroughAnInterfaceHierarchyCompiles() throws Exception {
    Type parent = type("Parent", TypeKind.INTERFACE);
    parent.setFields(List.of(field("pet", typeRef(TypeKind.INTERFACE, "Pet"), parent)));

    Type child = type("Child", TypeKind.INTERFACE);
    child.setInterfaces(List.of(typeRef(TypeKind.INTERFACE, "Parent")));
    child.setFields(List.of(field("pet", nonNull(typeRef(TypeKind.INTERFACE, "Pet")), child)));

    Type kennel = type("Kennel", TypeKind.OBJECT);
    kennel.setInterfaces(
        List.of(typeRef(TypeKind.INTERFACE, "Child"), typeRef(TypeKind.INTERFACE, "Parent")));
    kennel.setFields(List.of(field("pet", nonNull(typeRef(TypeKind.INTERFACE, "Pet")), kennel)));

    assertCompiles(sources(parent, child, kennel));
  }

  /**
   * Two unrelated interfaces may disagree on the nullability of the same field. An object
   * implementing both has a single method to satisfy both declarations, so they all have to return
   * Optional.
   */
  @Test
  void disagreeingUnrelatedInterfacesCompile() throws Exception {
    Type shelter = type("Shelter", TypeKind.INTERFACE);
    shelter.setFields(List.of(field("pet", typeRef(TypeKind.INTERFACE, "Pet"), shelter)));

    Type home = type("Home", TypeKind.INTERFACE);
    home.setFields(List.of(field("pet", nonNull(typeRef(TypeKind.INTERFACE, "Pet")), home)));

    Type kennel = type("Kennel", TypeKind.OBJECT);
    kennel.setInterfaces(
        List.of(typeRef(TypeKind.INTERFACE, "Shelter"), typeRef(TypeKind.INTERFACE, "Home")));
    kennel.setFields(List.of(field("pet", nonNull(typeRef(TypeKind.INTERFACE, "Pet")), kennel)));

    assertCompiles(sources(shelter, home, kennel));
  }

  /**
   * Coercing a non-null interface field to Optional must preserve GraphQL's covariant return type.
   * Otherwise the object's Optional&lt;Dog&gt; method cannot implement the interface's
   * Optional&lt;Animal&gt; method because Optional is invariant.
   */
  @Test
  void coercedNonNullInterfaceFieldPreservesCovariantReturn() throws Exception {
    Type shelter = type("Shelter", TypeKind.INTERFACE);
    shelter.setFields(List.of(field("pet", typeRef(TypeKind.INTERFACE, "Animal"), shelter)));

    Type home = type("Home", TypeKind.INTERFACE);
    home.setFields(List.of(field("pet", nonNull(typeRef(TypeKind.INTERFACE, "Animal")), home)));

    Type kennel = type("Kennel", TypeKind.OBJECT);
    kennel.setInterfaces(
        List.of(typeRef(TypeKind.INTERFACE, "Shelter"), typeRef(TypeKind.INTERFACE, "Home")));
    kennel.setFields(List.of(field("pet", nonNull(typeRef(TypeKind.OBJECT, "Dog")), kennel)));

    assertCompiles(sources(shelter, home, kennel));
  }

  /**
   * Interfaces that merely share an ancestor do not impose an override obligation on one another. A
   * nullable field on one sibling must not make a same-named non-null field on another sibling
   * Optional when their common ancestor does not declare that field.
   */
  @Test
  void nullableFieldDoesNotPropagateBetweenSiblingInterfaces() throws Exception {
    Type root = type("Root", TypeKind.INTERFACE);
    root.setFields(List.of(field("id", nonNull(typeRef(TypeKind.SCALAR, "ID")), root)));

    Type nullableSibling = type("NullableSibling", TypeKind.INTERFACE);
    nullableSibling.setInterfaces(List.of(typeRef(TypeKind.INTERFACE, "Root")));
    nullableSibling.setFields(
        List.of(field("child", typeRef(TypeKind.OBJECT, "Foo"), nullableSibling)));

    Type nonNullSibling = type("NonNullSibling", TypeKind.INTERFACE);
    nonNullSibling.setInterfaces(List.of(typeRef(TypeKind.INTERFACE, "Root")));
    nonNullSibling.setFields(
        List.of(field("child", nonNull(typeRef(TypeKind.OBJECT, "Foo")), nonNullSibling)));

    Type nullableImplementation = type("NullableImplementation", TypeKind.OBJECT);
    nullableImplementation.setInterfaces(
        List.of(
            typeRef(TypeKind.INTERFACE, "NullableSibling"), typeRef(TypeKind.INTERFACE, "Root")));
    nullableImplementation.setFields(
        List.of(field("child", typeRef(TypeKind.OBJECT, "Foo"), nullableImplementation)));

    Type implementation = type("NonNullImplementation", TypeKind.OBJECT);
    implementation.setInterfaces(
        List.of(
            typeRef(TypeKind.INTERFACE, "NonNullSibling"), typeRef(TypeKind.INTERFACE, "Root")));
    implementation.setFields(
        List.of(field("child", nonNull(typeRef(TypeKind.OBJECT, "Foo")), implementation)));

    Map<String, String> generated =
        sources(root, nullableSibling, nonNullSibling, nullableImplementation, implementation);

    assertThat(generated.get("io.dagger.client.NullableSibling")).contains("Optional<Foo> child()");
    assertThat(generated.get("io.dagger.client.NonNullSibling"))
        .contains("Foo child();")
        .doesNotContain("Optional<Foo> child()");
    assertThat(generated.get("io.dagger.client.NonNullImplementation"))
        .contains("Foo child()")
        .doesNotContain("Optional<Foo> child()");
  }

  /**
   * The generated sources for the given types, plus the handwritten ones they are compiled against.
   */
  private Map<String, String> sources(Type... types) throws Exception {
    Schema schema = schemaAtVersion("v1.0.0-beta.10");
    // The generators resolve the interfaces a type implements through the schema.
    schema.setTypes(List.of(types));

    Map<String, String> sources = new HashMap<>(supportSources());
    for (Type type : types) {
      String qualifiedName = "io.dagger.client." + type.getName();
      if (type.getKind() == TypeKind.INTERFACE) {
        InterfaceVisitor visitor =
            new InterfaceVisitor(schema, Path.of("."), StandardCharsets.UTF_8);
        sources.put(qualifiedName, javaFile(visitor.generateType(type)));
        sources.put(qualifiedName + "Client", javaFile(visitor.generateClientType(type)));
      } else {
        sources.put(
            qualifiedName,
            javaFile(
                new ObjectVisitor(schema, Path.of("."), StandardCharsets.UTF_8)
                    .generateType(type)));
      }
    }
    return sources;
  }

  /**
   * The client stub mirrors the real QueryBuilder, checked exceptions included: a generated method
   * that resolves a nullable object must declare them, and only a faithful stub can catch a missing
   * throws clause.
   */
  private static Map<String, String> supportSources() {
    return Map.of(
        "io.dagger.client.Animal",
        "package io.dagger.client; public interface Animal {}",
        "io.dagger.client.AnimalClient",
        "package io.dagger.client; public class AnimalClient implements Animal {"
            + " AnimalClient(QueryBuilder queryBuilder) {} }",
        "io.dagger.client.Dog",
        "package io.dagger.client; public class Dog implements Animal {"
            + " Dog(QueryBuilder queryBuilder) {} }",
        "io.dagger.client.Pet",
        "package io.dagger.client; public interface Pet {}",
        "io.dagger.client.PetClient",
        "package io.dagger.client; public class PetClient implements Pet {"
            + " PetClient(QueryBuilder queryBuilder) {} }",
        "io.dagger.client.QueryBuilder",
        "package io.dagger.client; public class QueryBuilder {"
            + " QueryBuilder chain(String field) { return this; }"
            + " QueryBuilder executeNullableObjectQuery(String typeName)"
            + " throws InterruptedException, java.util.concurrent.ExecutionException,"
            + " io.dagger.client.exception.DaggerQueryException { return this; }"
            + " }",
        "io.dagger.client.exception.DaggerQueryException",
        "package io.dagger.client.exception;"
            + " public class DaggerQueryException extends Exception {}");
  }

  private static String javaFile(TypeSpec typeSpec) {
    return JavaFile.builder("io.dagger.client", typeSpec).build().toString();
  }

  private static String generateInterface(Type type, String version) throws Exception {
    return new InterfaceVisitor(schemaAtVersion(version), Path.of("."), StandardCharsets.UTF_8)
        .generateType(type)
        .toString();
  }

  private static Schema schemaAtVersion(String version) throws Exception {
    byte[] introspection = "{\"__schema\":{\"types\":[]}}".getBytes(StandardCharsets.UTF_8);
    return Schema.initialize(new ByteArrayInputStream(introspection), version);
  }

  private static Type interfaceWithOptionalObjectField() {
    Type parent = type("Parent", TypeKind.INTERFACE);
    parent.setFields(List.of(field("child", typeRef(TypeKind.OBJECT, "Directory"), parent)));
    return parent;
  }

  private static Type type(String name, TypeKind kind) {
    Type type = new Type();
    type.setKind(kind);
    type.setName(name);
    type.setDescription("");
    type.setInterfaces(List.of());
    return type;
  }

  private static Field field(String name, TypeRef typeRef, Type parent) {
    Field field = new Field();
    field.setName(name);
    field.setDescription("");
    field.setTypeRef(typeRef);
    field.setArgs(List.of());
    field.setDirectives(List.of());
    field.setParentObject(parent);
    return field;
  }

  private static TypeRef typeRef(TypeKind kind, String name) {
    TypeRef ref = new TypeRef();
    ref.setKind(kind);
    ref.setName(name);
    return ref;
  }

  private static TypeRef nonNull(TypeRef inner) {
    TypeRef ref = new TypeRef();
    ref.setKind(TypeKind.NON_NULL);
    ref.setOfType(inner);
    return ref;
  }

  private void assertCompiles(Map<String, String> sources) {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertThat(compiler).isNotNull();

    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    List<JavaFileObject> compilationUnits =
        sources.entrySet().stream()
            .map(entry -> new SourceFile(entry.getKey(), entry.getValue()))
            .collect(Collectors.toList());
    boolean compiled =
        compiler
            .getTask(
                null,
                null,
                diagnostics,
                List.of(
                    "--release", "17", "-proc:none", "-d", compilationOutputDirectory.toString()),
                null,
                compilationUnits)
            .call();

    assertThat(compiled)
        .withFailMessage(
            "Generated sources did not compile:%n%s",
            diagnostics.getDiagnostics().stream()
                .map(Object::toString)
                .collect(Collectors.joining("\n")))
        .isTrue();
  }

  private static final class SourceFile extends SimpleJavaFileObject {
    private final String source;

    private SourceFile(String className, String source) {
      super(
          URI.create(
              "string:///" + className.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension),
          JavaFileObject.Kind.SOURCE);
      this.source = source;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return source;
    }
  }
}
