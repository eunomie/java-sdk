package io.dagger.codegen.introspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ClientEntryPointTest {

  /** The engine camel-cases a module's name to namespace its types, and {@code e2e} becomes E2E. */
  @Test
  void theRootTypeIsTheReturnTypeOfTheQueryFieldNotTheModuleName() throws Exception {
    ClientEntryPoint entry = entryPoint(E2E, "e2e");

    assertThat(entry.rootTypeName()).isEqualTo("E2E");
    assertThat(entry.entryField().getName()).isEqualTo("e2e");
  }

  @Test
  void aModulesFieldsOnOtherCoreTypesAreShims() throws Exception {
    assertThat(entryPoint(E2E, "e2e").shims()).containsOnlyKeys("Binding");
  }

  @Test
  void aModuleWithNoQueryFieldIsRefused() throws Exception {
    assertThatThrownBy(() -> entryPoint(NO_QUERY_FIELD, "e2e"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("module e2e owns 0 fields on Query")
        .hasMessageContaining("E2E");
  }

  @Test
  void aModuleWithTwoQueryFieldsIsRefused() throws Exception {
    assertThatThrownBy(() -> entryPoint(TWO_QUERY_FIELDS, "e2e"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("module e2e owns 2 fields on Query")
        .hasMessageContaining("[e2e, e2eAgain]");
  }

  /** A module named after a core type is reached as that type, and has no class of its own. */
  @Test
  void aModuleReachedAsACoreTypeItDoesNotOwnIsRefused() throws Exception {
    assertThatThrownBy(() -> entryPoint(NAMED_AFTER_A_CORE_TYPE, "container"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("module container is reached as the core type Container")
        .hasMessageContaining("Rename the module");
  }

  private static ClientEntryPoint entryPoint(String json, String module) throws Exception {
    Schema schema =
        Schema.initialize(
            new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "v1.0.0-beta.13");
    return new ClientEntryPoint(SchemaPartition.client(schema, module));
  }

  private static String owned(String module) {
    return "\"directives\": [{\"name\": \"sourceMap\", \"args\": [{\"name\": \"module\","
        + " \"value\": \"\\\""
        + module
        + "\\\"\"}]}]";
  }

  private static final String E2E =
      """
      {"__schema": {"queryType": {"name": "Query"}, "types": [
        {"kind": "OBJECT", "name": "Query", "fields": [
          {"name": "container", "type": {"kind": "OBJECT", "name": "Container"}, "args": []},
          {"name": "e2e", "type": {"kind": "OBJECT", "name": "E2E"}, "args": [], %s}
        ]},
        {"kind": "OBJECT", "name": "Container", "fields": []},
        {"kind": "OBJECT", "name": "Binding", "fields": [
          {"name": "asE2E", "type": {"kind": "OBJECT", "name": "E2E"}, "args": [], %s}
        ]},
        {"kind": "OBJECT", "name": "E2E", "fields": [], %s}
      ]}}
      """
          .formatted(owned("e2e"), owned("e2e"), owned("e2e"));

  private static final String NO_QUERY_FIELD =
      """
      {"__schema": {"queryType": {"name": "Query"}, "types": [
        {"kind": "OBJECT", "name": "Query", "fields": [
          {"name": "container", "type": {"kind": "OBJECT", "name": "Container"}, "args": []}
        ]},
        {"kind": "OBJECT", "name": "Container", "fields": []},
        {"kind": "OBJECT", "name": "E2E", "fields": [], %s}
      ]}}
      """
          .formatted(owned("e2e"));

  private static final String TWO_QUERY_FIELDS =
      """
      {"__schema": {"queryType": {"name": "Query"}, "types": [
        {"kind": "OBJECT", "name": "Query", "fields": [
          {"name": "e2e", "type": {"kind": "OBJECT", "name": "E2E"}, "args": [], %s},
          {"name": "e2eAgain", "type": {"kind": "OBJECT", "name": "E2E"}, "args": [], %s}
        ]},
        {"kind": "OBJECT", "name": "E2E", "fields": [], %s}
      ]}}
      """
          .formatted(owned("e2e"), owned("e2e"), owned("e2e"));

  private static final String NAMED_AFTER_A_CORE_TYPE =
      """
      {"__schema": {"queryType": {"name": "Query"}, "types": [
        {"kind": "OBJECT", "name": "Query", "fields": [
          {"name": "container", "type": {"kind": "OBJECT", "name": "Container"}, "args": [], %s}
        ]},
        {"kind": "OBJECT", "name": "Container", "fields": []},
        {"kind": "OBJECT", "name": "ContainerReport", "fields": [], %s}
      ]}}
      """
          .formatted(owned("container"), owned("container"));
}
