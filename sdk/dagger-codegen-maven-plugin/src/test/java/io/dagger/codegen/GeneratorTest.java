package io.dagger.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeneratorTest {

  @TempDir Path plan;
  @TempDir Path out;

  @Test
  void everyPackageThePlanNamesIsEmittedOnce() throws Exception {
    writePlan(CORE_WITH_TWO_TARGETS, target("alpha", ALPHA), target("beta", BETA));

    generate();

    assertThat(emitted())
        .contains(
            CORE + "Core.java",
            CORE + "Container.java",
            "io/dagger/client/modules/alpha/Alpha.java",
            "io/dagger/client/modules/alpha/AlphaReport.java",
            "io/dagger/client/modules/beta/Beta.java");
    assertThat(emitted()).doesNotContain(CORE + "Alpha.java");
  }

  /** A target is reached from its own package, so no core source names one. */
  @Test
  void coreNamesNoClientPackage() throws Exception {
    writePlan(CORE_WITH_TWO_TARGETS, target("alpha", ALPHA), target("beta", BETA));

    generate();

    for (String source : emitted()) {
      if (!source.startsWith(CORE)) {
        continue;
      }
      // Its own package taken out, so what is left is any client package core named.
      assertThat(read(source).replace(CORE_PACKAGE, ""))
          .as("core source %s", source)
          .doesNotContain("io.dagger.client.modules");
    }
    assertThat(read(CORE + "Core.java")).doesNotContain("Alpha").doesNotContain("Beta");
  }

  /**
   * Core is entered the way a target is: a static method on its own root type, over a session named
   * or ambient. What differs is that the session is already core, so there is nothing to select and
   * nothing to serve.
   */
  @Test
  void coreIsEnteredFromItsOwnPackage() throws Exception {
    writePlan(CORE_WITH_TWO_TARGETS, target("alpha", ALPHA), target("beta", BETA));

    generate();

    String core = read(CORE + "Core.java");
    assertThat(core)
        .contains("package io.dagger.client.modules.core;")
        .contains("import io.dagger.client.Session;")
        .contains("public static Core core(Session dag)")
        .contains("return new Core(dag.queryBuilder())")
        .contains("public static Core core()")
        .contains("return core(Dagger.dag())");
    assertThat(core).doesNotContain("ModuleTarget").doesNotContain("Connection");
    assertThat(emitted()).doesNotContain("io/dagger/client/Client.java");
  }

  /** The way into a target is a static method on its root type, taking the session it runs in. */
  @Test
  void aTargetIsEnteredFromItsOwnPackage() throws Exception {
    writePlan(CORE_WITH_TWO_TARGETS, target("alpha", ALPHA), target("beta", BETA));

    generate();

    assertThat(read("io/dagger/client/modules/alpha/Alpha.java"))
        .contains("import io.dagger.client.Session;")
        .contains("public static Alpha alpha(Session dag, String source)")
        .contains("public static Alpha alpha(String source)")
        .contains("return alpha(Dagger.dag(), source)");
    assertThat(read("io/dagger/client/modules/beta/Beta.java"))
        .contains("public static Beta beta(Session dag)")
        .contains("public static Beta beta()");
  }

  /** A target's optional constructor arguments travel with the method that takes them. */
  @Test
  void theArgumentsHolderOfAnEntryPointIsNestedInTheRootType() throws Exception {
    writePlan(CORE_WITH_TWO_TARGETS, target("alpha", ALPHA), target("beta", BETA));

    generate();

    assertThat(read("io/dagger/client/modules/alpha/Alpha.java"))
        .contains("public static class AlphaArguments")
        .contains("public static Alpha alpha(Session dag, String source, AlphaArguments optArgs)")
        .contains("public static Alpha alpha(String source, AlphaArguments optArgs)");
    assertThat(read(CORE + "Core.java")).doesNotContain("AlphaArguments");
  }

  /** A field a module contributes to another core type moves with it, receiver and all. */
  @Test
  void aTargetsContributionToACoreTypeIsEnteredFromItsOwnPackage() throws Exception {
    writePlan(CORE_WITH_TWO_TARGETS, target("alpha", ALPHA), target("beta", BETA));

    generate();

    assertThat(read("io/dagger/client/modules/alpha/Alpha.java"))
        .contains("import io.dagger.client.modules.core.Binding;")
        .contains("public static Alpha asAlpha(Binding binding)")
        .contains("binding.queryBuilder().chain(\"asAlpha\")");
    assertThat(read(CORE + "Binding.java")).doesNotContain("asAlpha");
    assertThat(emitted()).doesNotContain("io/dagger/client/modules/alpha/Binding.java");
  }

  /**
   * The receiver names the session the target has to be served into. Serving into whichever one
   * {@code Dagger.dag()} holds would land the target beside the caller's query rather than in it,
   * and the engine would then refuse the field with no hint as to why. So the receiver is the only
   * argument, and the session is read off it.
   */
  @Test
  void aContributionToACoreTypeIsServedIntoItsReceiversSession() throws Exception {
    writePlan(CORE_WITH_TWO_TARGETS, target("alpha", ALPHA), target("beta", BETA));

    generate();

    String alpha = read("io/dagger/client/modules/alpha/Alpha.java");
    assertThat(shimOf(alpha, "asAlpha"))
        .isEqualTo(
            """
                public static Alpha asAlpha(Binding binding) {
                    ModuleTargets.serve(new QueryBuilder(binding.queryBuilder().client()), TARGET);
                    QueryBuilder nextQueryBuilder = binding.queryBuilder().chain("asAlpha");
                    return new Alpha(nextQueryBuilder);
                }
            """
                .stripTrailing());
    assertThat(alpha).doesNotContain("asAlpha(Session dag");
  }

  @Test
  void aTargetsPackageReachesCoreTypesInTheCorePackage() throws Exception {
    writePlan(CORE_WITH_TWO_TARGETS, target("alpha", ALPHA), target("beta", BETA));

    generate();

    assertThat(read("io/dagger/client/modules/alpha/Alpha.java"))
        .contains("import io.dagger.client.modules.core.Container;");
  }

  /**
   * The field a target is reached through does not exist until something serves the target, so
   * every entry point asks for it first, off the descriptor its own package holds.
   */
  @Test
  void anEntryPointServesItsTargetFirst() throws Exception {
    writePlan(CORE_WITH_TWO_TARGETS, target("alpha", ALPHA), gitTarget("beta", BETA));

    generate();

    assertThat(read("io/dagger/client/modules/alpha/Alpha.java"))
        .contains(
            "private static final ModuleTarget TARGET = ModuleTarget.inWorkspace(\"alpha\","
                + " \"/dagger/modules/alpha\");")
        .contains("ModuleTargets.serve(dag.queryBuilder(), TARGET);");
    assertThat(read("io/dagger/client/modules/beta/Beta.java"))
        .contains(
            "private static final ModuleTarget TARGET = ModuleTarget.atGitRef(\"beta\","
                + " \"github.com/dagger/beta@v1\", \"0123abc\");")
        .contains("ModuleTargets.serve(dag.queryBuilder(), TARGET);");
    assertThat(read(CORE + "Core.java")).doesNotContain("ModuleTargets");
  }

  /**
   * A target the engine serves already — a local module reached from inside another module — gets
   * bindings that ask for nothing. The descriptor is what decides, so the two cases differ by one
   * file in the plan rather than by a switch in the generated code.
   */
  @Test
  void aTargetTheEngineServesCarriesNoDescriptorAndAsksForNothing() throws Exception {
    writePlan(
        CORE_WITH_TWO_TARGETS, servedByTheEngine("alpha", ALPHA), servedByTheEngine("beta", BETA));

    generate();

    String alpha = read("io/dagger/client/modules/alpha/Alpha.java");
    assertThat(alpha).doesNotContain("ModuleTarget").doesNotContain("TARGET");
    assertThat(alpha).contains("public static Alpha alpha(Session dag, String source)");
    assertThat(shimOf(alpha, "asAlpha"))
        .isEqualTo(
            """
                public static Alpha asAlpha(Binding binding) {
                    QueryBuilder nextQueryBuilder = binding.queryBuilder().chain("asAlpha");
                    return new Alpha(nextQueryBuilder);
                }
            """
                .stripTrailing());
  }

  @Test
  void aCoreTypeIsNotServed() throws Exception {
    writePlan(CORE_WITH_TWO_TARGETS, target("alpha", ALPHA), target("beta", BETA));

    generate();

    String container = read(CORE + "Container.java");
    assertThat(container).doesNotContain("ModuleTargets");
  }

  @Test
  void theSamePlanGeneratesTheSameBytesWhateverOrderItsEntriesAreLaidOutIn() throws Exception {
    writePlan(CORE_WITH_TWO_TARGETS, target("alpha", ALPHA), target("beta", BETA));
    generate();
    String first = read(CORE + "Core.java");

    Path reversed = Files.createTempDirectory("plan-reversed");
    writePlanAt(reversed, CORE_WITH_TWO_TARGETS, target("beta", BETA), target("alpha", ALPHA));
    Path secondOut = Files.createTempDirectory("out-reversed");
    new Generator(secondOut, StandardCharsets.UTF_8, VERSION)
        .generate(GenerationPlan.read(reversed));

    assertThat(Files.readString(secondOut.resolve(CORE + "Core.java"))).isEqualTo(first);
  }

  @Test
  void aModuleTheCoreSchemaMentionsButThePlanDoesNotGenerateIsRefused() throws Exception {
    writePlan(CORE_WITH_TWO_TARGETS, target("alpha", ALPHA));

    assertThatThrownBy(this::generate)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("beta");
  }

  /**
   * A standalone scope has no module-facing schema. Core is then whatever its targets agree on,
   * with everything any of them contributes taken out of it and emitted in that target's package.
   */
  @Test
  void withNoCoreSchemaCoreIsTakenFromTheTargets() throws Exception {
    writePlanWithoutCore(target("alpha", ALPHA), target("beta", BETA));

    generate();

    assertThat(read("io/dagger/client/modules/alpha/Alpha.java"))
        .contains("public static Alpha alpha(Session dag, String source)");
    assertThat(read("io/dagger/client/modules/beta/Beta.java"))
        .contains("public static Beta beta(Session dag)");
    assertThat(read(CORE + "Core.java")).doesNotContain("Alpha").doesNotContain("Beta");
    assertThat(read(CORE + "Container.java")).isNotEmpty();
    assertThat(emitted())
        .contains(
            "io/dagger/client/modules/alpha/Alpha.java", "io/dagger/client/modules/beta/Beta.java");
  }

  @Test
  void mergingCoreDoesNotDependOnTheOrderTheTargetsAreRead() throws Exception {
    writePlanWithoutCore(target("alpha", ALPHA), target("beta", BETA));
    generate();
    String first = read(CORE + "Core.java");

    Path reversed = Files.createTempDirectory("plan-reversed-merge");
    writePlanAt(reversed, null, target("beta", BETA), target("alpha", ALPHA));
    Path secondOut = Files.createTempDirectory("out-reversed-merge");
    new Generator(secondOut, StandardCharsets.UTF_8, VERSION)
        .generate(GenerationPlan.read(reversed));

    assertThat(Files.readString(secondOut.resolve(CORE + "Core.java"))).isEqualTo(first);
  }

  /**
   * The engine renders core through each module's declared engine version, so two targets pinned to
   * different ones hand back two different cores.
   */
  @Test
  void targetsThatDisagreeAboutCoreAreRefused() throws Exception {
    writePlanWithoutCore(target("alpha", ALPHA), target("beta", BETA_ON_A_NARROWER_CORE));

    assertThatThrownBy(this::generate)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("do not agree on the core")
        .hasMessageContaining("alpha")
        .hasMessageContaining("beta has no type Binding");
  }

  /** Two cores can name exactly the same types and fields and still be different cores. */
  @Test
  void targetsThatAgreeOnEveryNameButNotOnAFieldTypeAreRefused() throws Exception {
    writePlanWithoutCore(target("alpha", ALPHA), target("beta", BETA_RETURNING_ANOTHER_CORE_TYPE));

    assertThatThrownBy(this::generate)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Query.container returns Container! in alpha and Directory! in beta");
  }

  /**
   * A module scope has a core of its own, and its client packages are dropped next to it. A target
   * rendered through a different engine version would compile against the wrong one.
   */
  @Test
  void aTargetThatDisagreesWithTheModuleScopesCoreIsRefused() throws Exception {
    writePlan(
        CORE_WITH_TWO_TARGETS,
        target("alpha", ALPHA_RETURNING_ANOTHER_CORE_TYPE),
        target("beta", BETA));

    assertThatThrownBy(this::generate)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("the module scope")
        .hasMessageContaining("alpha")
        .hasMessageContaining(
            "Query.container returns Container! in the module scope and Directory! in alpha");
  }

  /**
   * The engine hides part of core from module code, so a target's client-facing core holds types
   * and fields the module-facing one does not. That is the healthy case, not skew.
   */
  @Test
  void aTargetWhoseCoreIsWiderThanTheModuleScopesIsAccepted() throws Exception {
    writePlan(CORE_WITH_TWO_TARGETS, target("alpha", ALPHA_ON_A_WIDER_CORE), target("beta", BETA));

    generate();

    assertThat(emitted()).contains("io/dagger/client/modules/alpha/Alpha.java");
    assertThat(emitted()).doesNotContain(CORE + "Host.java");
  }

  /**
   * A target's local types are namespaced under its own name, so {@code foo} and {@code foo-bar}
   * can both claim {@code FooBar} while their packages, {@code foo} and {@code foobar}, do not
   * collide.
   */
  @Test
  void twoTargetsOwningOneTypeNameAreRefused() throws Exception {
    writePlan(
        CORE_WITH_TWO_TARGETS, target("alpha", ALPHA), target("beta", BETA_OWNING_ALPHAS_TYPE));

    assertThatThrownBy(this::generate)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("alpha")
        .hasMessageContaining("beta")
        .hasMessageContaining("AlphaReport");
  }

  @Test
  void aTargetOwningATypeNameCoreAlsoHasIsRefused() throws Exception {
    writePlan(
        CORE_WITH_TWO_TARGETS, target("alpha", ALPHA_OWNING_A_CORE_TYPE), target("beta", BETA));

    assertThatThrownBy(this::generate)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("alpha")
        .hasMessageContaining("Container");
  }

  /** Where core lands: a package under the modules root, like every other client. */
  private static final String CORE_PACKAGE = "io.dagger.client.modules.core";

  private static final String CORE = CORE_PACKAGE.replace('.', '/') + "/";

  private void generate() throws IOException {
    new Generator(out, StandardCharsets.UTF_8, VERSION).generate(GenerationPlan.read(plan));
  }

  private record Entry(String module, String schema, String source) {}

  /** A target the client loads itself, from the workspace. */
  private static Entry target(String module, String schema) {
    return new Entry(module, schema, "workspace\n/dagger/modules/" + module);
  }

  /** The same, from git at a pin. */
  private static Entry gitTarget(String module, String schema) {
    return new Entry(module, schema, "git\ngithub.com/dagger/" + module + "@v1\n0123abc");
  }

  /** A target the engine serves on its own, so the client asks for nothing. */
  private static Entry servedByTheEngine(String module, String schema) {
    return new Entry(module, schema, null);
  }

  private void writePlan(String coreSchema, Entry... targets) throws IOException {
    writePlanAt(plan, coreSchema, targets);
  }

  private void writePlanWithoutCore(Entry... targets) throws IOException {
    writePlanAt(plan, null, targets);
  }

  private static void writePlanAt(Path root, String coreSchema, Entry... targets)
      throws IOException {
    if (coreSchema != null) {
      Files.createDirectories(root.resolve("core"));
      Files.writeString(root.resolve("core/schema.json"), coreSchema);
    }
    for (int i = 0; i < targets.length; i++) {
      Path entry = root.resolve("target-" + i);
      Files.createDirectories(entry);
      Files.writeString(entry.resolve("schema.json"), targets[i].schema());
      Files.writeString(entry.resolve("module"), targets[i].module());
      if (targets[i].source() != null) {
        Files.writeString(entry.resolve("source"), targets[i].source());
      }
    }
  }

  private List<String> emitted() throws IOException {
    try (Stream<Path> files = Files.walk(out)) {
      return files
          .filter(Files::isRegularFile)
          .map(path -> out.relativize(path).toString())
          .sorted()
          .toList();
    }
  }

  /** One generated method, from its signature to the line that closes it. */
  private static String shimOf(String source, String name) {
    List<String> lines = source.lines().toList();
    int start =
        IntStream.range(0, lines.size())
            .filter(i -> lines.get(i).contains(" " + name + "("))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no method named " + name));
    int end = start;
    while (!lines.get(end).equals("    }")) {
      end++;
    }
    return String.join("\n", lines.subList(start, end + 1));
  }

  private String read(String relative) throws IOException {
    return Files.readString(out.resolve(relative));
  }

  private static final String VERSION = "v1.0.0-beta.13";

  private static String owned(String module) {
    return "\"directives\": [{\"name\": \"sourceMap\", \"args\": [{\"name\": \"module\","
        + " \"value\": \"\\\""
        + module
        + "\\\"\"}]}]";
  }

  private static final String CORE_WITH_TWO_TARGETS =
      """
      {"__schema": {"queryType": {"name": "Query"}, "types": [
        {"kind": "OBJECT", "name": "Query", "fields": [
          {"name": "container", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Container"}}, "args": []},
          {"name": "alpha", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Alpha"}}, "args": [{"name": "source", "type": {"kind": "NON_NULL", "ofType": {"kind": "SCALAR", "name": "String"}}}, {"name": "tag", "type": {"kind": "SCALAR", "name": "String"}}], %s},
          {"name": "beta", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Beta"}}, "args": [], %s}
        ]},
        {"kind": "OBJECT", "name": "Container", "fields": []},
        {"kind": "OBJECT", "name": "Binding", "fields": [
          {"name": "asAlpha", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Alpha"}}, "args": [], %s}
        ]},
        {"kind": "OBJECT", "name": "Alpha", "fields": [], %s},
        {"kind": "OBJECT", "name": "AlphaReport", "fields": [], %s},
        {"kind": "OBJECT", "name": "Beta", "fields": [], %s}
      ]}}
      """
          .formatted(
              owned("alpha"),
              owned("beta"),
              owned("alpha"),
              owned("alpha"),
              owned("alpha"),
              owned("beta"));

  private static final String ALPHA =
      """
      {"__schema": {"queryType": {"name": "Query"}, "types": [
        {"kind": "OBJECT", "name": "Query", "fields": [
          {"name": "container", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Container"}}, "args": []},
          {"name": "alpha", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Alpha"}}, "args": [{"name": "source", "type": {"kind": "NON_NULL", "ofType": {"kind": "SCALAR", "name": "String"}}}, {"name": "tag", "type": {"kind": "SCALAR", "name": "String"}}], %s}
        ]},
        {"kind": "OBJECT", "name": "Container", "fields": []},
        {"kind": "OBJECT", "name": "Binding", "fields": [
          {"name": "asAlpha", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Alpha"}}, "args": [], %s}
        ]},
        {"kind": "OBJECT", "name": "Alpha", "fields": [
          {"name": "base", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Container"}}, "args": []}
        ], %s},
        {"kind": "OBJECT", "name": "AlphaReport", "fields": [], %s}
      ]}}
      """
          .formatted(owned("alpha"), owned("alpha"), owned("alpha"), owned("alpha"));

  /**
   * Alpha's client-facing core carries a type and a field the module-facing core does not, the way
   * a real one carries Host and Query.host.
   */
  private static final String ALPHA_ON_A_WIDER_CORE =
      """
      {"__schema": {"queryType": {"name": "Query"}, "types": [
        {"kind": "OBJECT", "name": "Query", "fields": [
          {"name": "container", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Container"}}, "args": []},
          {"name": "host", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Host"}}, "args": []},
          {"name": "alpha", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Alpha"}}, "args": [], %s}
        ]},
        {"kind": "OBJECT", "name": "Container", "fields": []},
        {"kind": "OBJECT", "name": "Host", "fields": []},
        {"kind": "OBJECT", "name": "Binding", "fields": []},
        {"kind": "OBJECT", "name": "Alpha", "fields": [], %s},
        {"kind": "OBJECT", "name": "AlphaReport", "fields": [], %s}
      ]}}
      """
          .formatted(owned("alpha"), owned("alpha"), owned("alpha"));

  /** Beta owns a type alpha owns too, as two targets whose namespaced names meet would. */
  private static final String BETA_OWNING_ALPHAS_TYPE =
      """
      {"__schema": {"queryType": {"name": "Query"}, "types": [
        {"kind": "OBJECT", "name": "Query", "fields": [
          {"name": "container", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Container"}}, "args": []},
          {"name": "beta", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Beta"}}, "args": [], %s}
        ]},
        {"kind": "OBJECT", "name": "Container", "fields": []},
        {"kind": "OBJECT", "name": "Beta", "fields": [], %s},
        {"kind": "OBJECT", "name": "AlphaReport", "fields": [], %s}
      ]}}
      """
          .formatted(owned("beta"), owned("beta"), owned("beta"));

  /** Alpha owns a type core has, which no target may take over. */
  private static final String ALPHA_OWNING_A_CORE_TYPE =
      """
      {"__schema": {"queryType": {"name": "Query"}, "types": [
        {"kind": "OBJECT", "name": "Query", "fields": [
          {"name": "alpha", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Alpha"}}, "args": [{"name": "source", "type": {"kind": "NON_NULL", "ofType": {"kind": "SCALAR", "name": "String"}}}, {"name": "tag", "type": {"kind": "SCALAR", "name": "String"}}], %s}
        ]},
        {"kind": "OBJECT", "name": "Container", "fields": [], %s},
        {"kind": "OBJECT", "name": "Alpha", "fields": [], %s},
        {"kind": "OBJECT", "name": "AlphaReport", "fields": [], %s}
      ]}}
      """
          .formatted(owned("alpha"), owned("alpha"), owned("alpha"), owned("alpha"));

  /** Beta's core is missing a type alpha's has, as a pre-1.0 engine view would be. */
  private static final String BETA_ON_A_NARROWER_CORE =
      """
      {"__schema": {"queryType": {"name": "Query"}, "types": [
        {"kind": "OBJECT", "name": "Query", "fields": [
          {"name": "beta", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Beta"}}, "args": [], %s}
        ]},
        {"kind": "OBJECT", "name": "Beta", "fields": [], %s}
      ]}}
      """
          .formatted(owned("beta"), owned("beta"));

  private static final String BETA =
      """
      {"__schema": {"queryType": {"name": "Query"}, "types": [
        {"kind": "OBJECT", "name": "Query", "fields": [
          {"name": "container", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Container"}}, "args": []},
          {"name": "beta", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Beta"}}, "args": [], %s}
        ]},
        {"kind": "OBJECT", "name": "Container", "fields": []},
        {"kind": "OBJECT", "name": "Binding", "fields": []},
        {"kind": "OBJECT", "name": "Beta", "fields": [], %s}
      ]}}
      """
          .formatted(owned("beta"), owned("beta"));

  /**
   * Beta renames nothing: its core has exactly alpha's types and fields, and one of them returns
   * something else.
   */
  private static final String BETA_RETURNING_ANOTHER_CORE_TYPE =
      """
      {"__schema": {"queryType": {"name": "Query"}, "types": [
        {"kind": "OBJECT", "name": "Query", "fields": [
          {"name": "container", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Directory"}}, "args": []},
          {"name": "beta", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Beta"}}, "args": [], %s}
        ]},
        {"kind": "OBJECT", "name": "Container", "fields": []},
        {"kind": "OBJECT", "name": "Directory", "fields": []},
        {"kind": "OBJECT", "name": "Binding", "fields": []},
        {"kind": "OBJECT", "name": "Beta", "fields": [], %s}
      ]}}
      """
          .formatted(owned("beta"), owned("beta"));

  /** Alpha's core disagrees with the module scope's about what {@code Query.container} returns. */
  private static final String ALPHA_RETURNING_ANOTHER_CORE_TYPE =
      """
      {"__schema": {"queryType": {"name": "Query"}, "types": [
        {"kind": "OBJECT", "name": "Query", "fields": [
          {"name": "container", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Directory"}}, "args": []},
          {"name": "alpha", "type": {"kind": "NON_NULL", "ofType": {"kind": "OBJECT", "name": "Alpha"}}, "args": [], %s}
        ]},
        {"kind": "OBJECT", "name": "Container", "fields": []},
        {"kind": "OBJECT", "name": "Directory", "fields": []},
        {"kind": "OBJECT", "name": "Binding", "fields": []},
        {"kind": "OBJECT", "name": "Alpha", "fields": [], %s},
        {"kind": "OBJECT", "name": "AlphaReport", "fields": [], %s}
      ]}}
      """
          .formatted(owned("alpha"), owned("alpha"), owned("alpha"));
}
