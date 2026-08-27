package io.dagger.codegen.introspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.palantir.javapoet.JavaFile;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModuleClientCodegenTest {

  private static final ClientBinding LOCAL =
      new ClientBinding("hello", "LOCAL_SOURCE", "dagger/modules/hello", "");
  private static final ClientBinding GIT =
      new ClientBinding("hello", "GIT_SOURCE", "github.com/dagger/hello", "0123abc");

  @TempDir Path compilationOutputDirectory;

  @Test
  void theRootTypeGetsAFactoryThatServesTheModuleAndTakesItsConstructorArguments()
      throws Exception {
    String hello = generate(Fixtures.clientSchema("hello", "Hello", "hello", "asHello"), LOCAL);
    assertThat(hello)
        .contains("public static Hello from(Session dag, String name)")
        .contains("public static Hello from(Session dag, String name, HelloArguments optArgs)")
        .contains("QueryBuilder root = dag.queryBuilder().root();")
        .contains(
            "ModuleBinding.ensureServed(root, \"hello\", \"LOCAL_SOURCE\", \"dagger/modules/hello\", \"\");")
        .contains("dag.queryBuilder().chain(\"hello\", fieldArgs)");
  }

  @Test
  void theAliasIsNamedAfterTheModuleAndDelegates() throws Exception {
    String hello = generate(Fixtures.clientSchema("hello", "Hello", "hello", "asHello"), LOCAL);
    assertThat(hello)
        .contains("public static Hello hello(Session dag, String name)")
        .contains("return from(dag, name);")
        .contains("public static Hello hello(Session dag, String name, HelloArguments optArgs)")
        .contains("return from(dag, name, optArgs);");
  }

  @Test
  void aGitBindingBakesItsRefAndPin() throws Exception {
    String hello = generate(Fixtures.clientSchema("hello", "Hello", "hello", "asHello"), GIT);
    assertThat(hello)
        .contains(
            "ModuleBinding.ensureServed(root, \"hello\", \"GIT_SOURCE\", \"github.com/dagger/hello\", \"0123abc\");");
  }

  @Test
  void aModulesFieldOnACoreTypeBecomesAStaticShim() throws Exception {
    String hello = generate(Fixtures.clientSchema("hello", "Hello", "hello", "asHello"), LOCAL);
    // Binding.asHello is nullable in the fixture, so the shim resolves to Optional like any
    // nullable object field would on an instance method.
    assertThat(hello)
        .contains("public static Optional<Hello> asHello(Binding binding)")
        .contains("QueryBuilder root = binding.queryBuilder().root();")
        .contains("binding.queryBuilder().chain(\"asHello\")")
        .contains("executeNullableObjectQuery(\"Hello\")");
  }

  @Test
  void twoShimsOfTheSameFieldNameGetHelperClassesOfTheirOwn() throws Exception {
    String hello = generate(Fixtures.twoShimsSchema("hello", "Hello", "hello", "configure"), LOCAL);
    assertThat(hello)
        .contains("public static class ContainerConfigureArguments")
        .contains("public static class WorkspaceConfigureArguments")
        .contains(
            "public static Hello configure(Container container, ContainerConfigureArguments optArgs)")
        .contains(
            "public static Hello configure(Workspace workspace, WorkspaceConfigureArguments optArgs)");

    Map<String, String> sources = new HashMap<>(runtimeStubs());
    sources.put(
        "io.dagger.core.Workspace",
        "package io.dagger.core; public class Workspace {"
            + " public io.dagger.sdk.QueryBuilder queryBuilder() { return null; } }");
    sources.put(
        "io.dagger.core.Container",
        "package io.dagger.core; public class Container {"
            + " public io.dagger.sdk.QueryBuilder queryBuilder() { return null; } }");
    sources.put("io.dagger.client.hello.Hello", hello);
    CompileSupport.assertCompiles(compilationOutputDirectory, sources);
  }

  @Test
  void theRootTypeComesFromTheSchemaNotFromTheModuleName() throws Exception {
    ClientBinding e2e = new ClientBinding("e2e", "LOCAL_SOURCE", ".dagger/modules/e2e", "");
    ClientEntryPoint entryPoint =
        new ClientEntryPoint(
            SchemaPartition.client(
                parse(Fixtures.clientSchema("e2e", "E2E", "e2E", "asE2E")), "e2e"),
            e2e);
    assertThat(entryPoint.rootTypeName()).isEqualTo("E2E");
    assertThat(entryPoint.entryField().getName()).isEqualTo("e2E");
    String root = generate(Fixtures.clientSchema("e2e", "E2E", "e2E", "asE2E"), e2e);
    assertThat(root).contains("public class E2E").contains("public static E2E e2E(Session dag");
  }

  @Test
  void theGeneratedClientCompilesAgainstCoreAndTheRuntime() throws Exception {
    Map<String, String> sources = new HashMap<>(runtimeStubs());
    sources.put(
        "io.dagger.client.hello.Hello",
        generate(Fixtures.clientSchema("hello", "Hello", "hello", "asHello"), LOCAL));
    CompileSupport.assertCompiles(compilationOutputDirectory, sources);
  }

  @Test
  void aBindingForAnotherModuleThanThePartitionIsRejected() throws Exception {
    SchemaPartition hello =
        SchemaPartition.client(
            parse(Fixtures.clientSchema("hello", "Hello", "hello", "asHello")), "hello");
    assertThatThrownBy(() -> new ClientEntryPoint(hello, GIT.withModule("other")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("other")
        .hasMessageContaining("hello");
  }

  @Test
  void aModuleNamedAfterACoreTypeIsRejected() throws Exception {
    ClientBinding container =
        new ClientBinding("container", "LOCAL_SOURCE", "/modules/container", "");
    SchemaPartition partition =
        SchemaPartition.client(
            parse(Fixtures.coreNamedSchema("container", "container")), "container");
    assertThatThrownBy(() -> new ClientEntryPoint(partition, container))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("module container")
        .hasMessageContaining("core type Container");
  }

  @Test
  void schemaArgumentsThatWouldShadowAGeneratedLocalAreEscaped() throws Exception {
    assertThat(Helpers.formatName(arg("dag"))).isEqualTo("_dag");
    assertThat(Helpers.formatName(arg("root"))).isEqualTo("_root");
    assertThat(Helpers.formatName(arg("builder"))).isEqualTo("_builder");
    assertThat(Helpers.formatName(arg("fieldArgs"))).isEqualTo("_fieldArgs");
    assertThat(Helpers.formatName(arg("optArgs"))).isEqualTo("_optArgs");
    assertThat(Helpers.formatName(arg("nextQueryBuilder"))).isEqualTo("_nextQueryBuilder");
    assertThat(Helpers.formatName(arg("objectQueryBuilder"))).isEqualTo("_objectQueryBuilder");
    assertThat(Helpers.formatName(arg("builders"))).isEqualTo("_builders");
    assertThat(Helpers.formatName(arg("name"))).isEqualTo("name");

    String hello =
        generate(
            Fixtures.clientSchema("hello", "Hello", "hello", "asHello", "dag", "builder"), LOCAL);
    assertThat(hello)
        .contains("public static Hello from(Session dag, String _dag)")
        .contains("builder.add(\"dag\", _dag)")
        .contains("public HelloArguments withBuilder(String _builder)");

    Map<String, String> sources = new HashMap<>(runtimeStubs());
    sources.put("io.dagger.client.hello.Hello", hello);
    CompileSupport.assertCompiles(compilationOutputDirectory, sources);
  }

  private static InputObject arg(String name) {
    InputObject arg = new InputObject();
    arg.setName(name);
    return arg;
  }

  @Test
  void packageSegmentsAreLegalJava() {
    assertThat(Helpers.packageSegment("hello")).isEqualTo("hello");
    assertThat(Helpers.packageSegment("my-module")).isEqualTo("mymodule");
    assertThat(Helpers.packageSegment("Java_SDK")).isEqualTo("javasdk");
    assertThat(Helpers.packageSegment("1st")).isEqualTo("_1st");
    assertThat(Helpers.packageSegment("package")).isEqualTo("package_");
    assertThatThrownBy(() -> Helpers.packageSegment("---"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** The root type's source, generated as the module's client package would. */
  private static String generate(String schemaJson, ClientBinding binding) throws Exception {
    Schema schema = parse(schemaJson);
    SchemaPartition client = SchemaPartition.client(schema, binding.module());
    ClientEntryPoint entryPoint = new ClientEntryPoint(client, binding);
    String pkg = "io.dagger.client." + Helpers.packageSegment(binding.module());
    TypeRegistry registry =
        TypeRegistry.client(pkg, "io.dagger.core", "io.dagger.sdk", client.ownedTypeNames());
    ObjectVisitor visitor =
        new ObjectVisitor(schema, registry, entryPoint, Path.of("."), StandardCharsets.UTF_8);
    Type root =
        client.types().stream()
            .filter(t -> t.getName().equals(entryPoint.rootTypeName()))
            .findFirst()
            .orElseThrow();
    return JavaFile.builder(pkg, visitor.generateType(root)).build().toString();
  }

  private static Schema parse(String json) throws Exception {
    return Schema.initialize(
        new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "v1.0.0-beta.10");
  }

  /** Just enough of io.dagger.core and io.dagger.sdk for a client's root type to compile. */
  private static Map<String, String> runtimeStubs() {
    return Map.of(
        "io.dagger.sdk.QueryBuilder",
        "package io.dagger.sdk; public class QueryBuilder {"
            + " public QueryBuilder root() { return this; }"
            + " public QueryBuilder chain(String f) { return this; }"
            + " public QueryBuilder chain(String f, Arguments a) { return this; }"
            + " public QueryBuilder executeNullableObjectQuery(String t)"
            + " throws java.util.concurrent.ExecutionException, InterruptedException,"
            + " io.dagger.sdk.exception.DaggerQueryException { return this; }"
            + " public <T> T executeQuery(Class<T> c) { return null; } }",
        "io.dagger.sdk.Arguments",
        "package io.dagger.sdk; public class Arguments {"
            + " public static Builder newBuilder() { return new Builder(); }"
            + " public Arguments merge(Arguments o) { return this; }"
            + " public static class Builder {"
            + "  public Builder add(String n, String v) { return this; }"
            + "  public Arguments build() { return new Arguments(); } } }",
        "io.dagger.sdk.ModuleBinding",
        "package io.dagger.sdk; public final class ModuleBinding {"
            + " public static void ensureServed(QueryBuilder q, String n, String k, String r, String p)"
            + " throws java.util.concurrent.ExecutionException, InterruptedException,"
            + " io.dagger.sdk.exception.DaggerQueryException {} }",
        "io.dagger.sdk.exception.DaggerQueryException",
        "package io.dagger.sdk.exception; public class DaggerQueryException extends Exception {}",
        "io.dagger.sdk.Session",
        "package io.dagger.sdk; public final class Session {"
            + " public QueryBuilder queryBuilder() { return null; } }",
        "io.dagger.core.Binding",
        "package io.dagger.core; public class Binding {"
            + " public io.dagger.sdk.QueryBuilder queryBuilder() { return null; } }");
  }
}
