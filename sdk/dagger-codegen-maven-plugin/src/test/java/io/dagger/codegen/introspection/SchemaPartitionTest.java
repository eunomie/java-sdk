package io.dagger.codegen.introspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SchemaPartitionTest {

  @Test
  void coreKeepsEveryUnownedType() throws Exception {
    SchemaPartition core = SchemaPartition.core(parse(TWO_MODULES));

    assertThat(names(core)).containsExactlyInAnyOrder("Query", "Container", "Binding");
  }

  /** Core depends on no client package, so nothing a module contributes stays on it. */
  @Test
  void coreDropsTheFieldsModulesContributeToCoreTypes() throws Exception {
    SchemaPartition core = SchemaPartition.core(parse(TWO_MODULES));

    assertThat(fieldNames(core, "Query")).containsExactly("container");
    assertThat(fieldNames(core, "Binding")).isEmpty();
  }

  @Test
  void aClientKeepsItsOwnTypes() throws Exception {
    Schema schema = parse(TWO_MODULES);

    assertThat(names(SchemaPartition.client(schema, "alpha")))
        .containsExactlyInAnyOrder("Alpha", "AlphaReport");
    assertThat(names(SchemaPartition.client(schema, "beta"))).containsExactly("Beta");
  }

  /** What core drops the contributing module picks up, on {@code Query} and elsewhere alike. */
  @Test
  void aClientKeepsTheFieldsItsModuleContributesToCoreTypes() throws Exception {
    Schema schema = parse(TWO_MODULES);

    assertThat(extensionNames(SchemaPartition.client(schema, "alpha")))
        .containsExactly(entry("Binding", List.of("asAlpha")), entry("Query", List.of("alpha")));
    assertThat(extensionNames(SchemaPartition.client(schema, "beta")))
        .containsExactly(entry("Query", List.of("beta")));
  }

  @Test
  void aClientPartitionDoesNotDependOnWhichOtherModulesAreInTheSchema() throws Exception {
    SchemaPartition fromBoth = SchemaPartition.client(parse(TWO_MODULES), "alpha");
    SchemaPartition fromAlone = SchemaPartition.client(parse(ALPHA_ONLY), "alpha");

    assertThat(names(fromBoth)).isEqualTo(names(fromAlone));
    assertThat(extensionNames(fromBoth)).isEqualTo(extensionNames(fromAlone));
  }

  @Test
  void aCoreOnlySchemaPartitionsToItself() throws Exception {
    Schema schema = parse(CORE_ONLY);

    assertThat(names(SchemaPartition.core(schema))).containsExactlyInAnyOrder("Query", "Container");
  }

  /** The engine yields core alone for a module source whose SDK does not resolve as a runtime. */
  @Test
  void aModuleWithNothingOwnedIsRefused() throws Exception {
    Schema schema = parse(CORE_ONLY);

    assertThatThrownBy(() -> SchemaPartition.client(schema, "alpha"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("alpha")
        .hasMessageContaining("not a runtime");
  }

  @Test
  void ownedModulesReportsEveryModuleTheSchemaMentions() throws Exception {
    assertThat(SchemaPartition.ownedModules(parse(TWO_MODULES))).containsExactly("alpha", "beta");
    assertThat(SchemaPartition.ownedModules(parse(CORE_ONLY))).isEmpty();
  }

  private static Schema parse(String json) throws Exception {
    return Schema.initialize(
        new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "v1.0.0-beta.13");
  }

  private static List<String> names(SchemaPartition partition) {
    return partition.types().stream().map(Type::getName).toList();
  }

  private static Map<String, List<String>> extensionNames(SchemaPartition partition) {
    Map<String, List<String>> names = new LinkedHashMap<>();
    partition
        .extensions()
        .forEach((type, fields) -> names.put(type, fields.stream().map(Field::getName).toList()));
    return names;
  }

  private static List<String> fieldNames(SchemaPartition partition, String typeName) {
    return partition.types().stream()
        .filter(type -> typeName.equals(type.getName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no type named " + typeName))
        .getFields()
        .stream()
        .map(Field::getName)
        .toList();
  }

  private static final String TWO_MODULES =
      """
      {"__schema": {"queryType": {"name": "Query"}, "types": [
        {"kind": "OBJECT", "name": "Query", "fields": [
          {"name": "container", "type": {"kind": "OBJECT", "name": "Container"}, "args": []},
          {"name": "alpha", "type": {"kind": "OBJECT", "name": "Alpha"}, "args": [],
           "directives": [{"name": "sourceMap", "args": [{"name": "module", "value": "\\"alpha\\""}]}]},
          {"name": "beta", "type": {"kind": "OBJECT", "name": "Beta"}, "args": [],
           "directives": [{"name": "sourceMap", "args": [{"name": "module", "value": "\\"beta\\""}]}]}
        ]},
        {"kind": "OBJECT", "name": "Container", "fields": []},
        {"kind": "OBJECT", "name": "Binding", "fields": [
          {"name": "asAlpha", "type": {"kind": "OBJECT", "name": "Alpha"}, "args": [],
           "directives": [{"name": "sourceMap", "args": [{"name": "module", "value": "\\"alpha\\""}]}]}
        ]},
        {"kind": "OBJECT", "name": "Alpha", "fields": [],
         "directives": [{"name": "sourceMap", "args": [{"name": "module", "value": "\\"alpha\\""}]}]},
        {"kind": "OBJECT", "name": "AlphaReport", "fields": [],
         "directives": [{"name": "sourceMap", "args": [{"name": "module", "value": "\\"alpha\\""}]}]},
        {"kind": "OBJECT", "name": "Beta", "fields": [],
         "directives": [{"name": "sourceMap", "args": [{"name": "module", "value": "\\"beta\\""}]}]}
      ]}}
      """;

  private static final String ALPHA_ONLY =
      """
      {"__schema": {"queryType": {"name": "Query"}, "types": [
        {"kind": "OBJECT", "name": "Query", "fields": [
          {"name": "container", "type": {"kind": "OBJECT", "name": "Container"}, "args": []},
          {"name": "alpha", "type": {"kind": "OBJECT", "name": "Alpha"}, "args": [],
           "directives": [{"name": "sourceMap", "args": [{"name": "module", "value": "\\"alpha\\""}]}]}
        ]},
        {"kind": "OBJECT", "name": "Container", "fields": []},
        {"kind": "OBJECT", "name": "Binding", "fields": [
          {"name": "asAlpha", "type": {"kind": "OBJECT", "name": "Alpha"}, "args": [],
           "directives": [{"name": "sourceMap", "args": [{"name": "module", "value": "\\"alpha\\""}]}]}
        ]},
        {"kind": "OBJECT", "name": "Alpha", "fields": [],
         "directives": [{"name": "sourceMap", "args": [{"name": "module", "value": "\\"alpha\\""}]}]},
        {"kind": "OBJECT", "name": "AlphaReport", "fields": [],
         "directives": [{"name": "sourceMap", "args": [{"name": "module", "value": "\\"alpha\\""}]}]}
      ]}}
      """;

  private static final String CORE_ONLY =
      """
      {"__schema": {"queryType": {"name": "Query"}, "types": [
        {"kind": "OBJECT", "name": "Query", "fields": [
          {"name": "container", "type": {"kind": "OBJECT", "name": "Container"}, "args": []}
        ]},
        {"kind": "OBJECT", "name": "Container", "fields": []}
      ]}}
      """;
}
