package io.dagger.codegen.introspection;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SourceMapAttributionTest {

  @Test
  void aTypeTheEngineAttributesToAModuleReportsThatModule() throws Exception {
    Schema schema = parse(SCHEMA);

    assertThat(typeNamed(schema, "ClientDep").getOwningModule()).isEqualTo("client-dep");
  }

  @Test
  void aCoreTypeReportsNoModule() throws Exception {
    Schema schema = parse(SCHEMA);

    assertThat(typeNamed(schema, "Container").getOwningModule()).isNull();
  }

  @Test
  void aFieldAModuleContributesToACoreTypeReportsThatModule() throws Exception {
    Schema schema = parse(SCHEMA);
    Type query = typeNamed(schema, "Query");

    assertThat(fieldNamed(query, "clientDep").getOwningModule()).isEqualTo("client-dep");
    assertThat(fieldNamed(query, "container").getOwningModule()).isNull();
  }

  @Test
  void aDirectiveWithNoModuleArgumentOrAnEmptyOneReportsNoModule() throws Exception {
    Schema schema = parse(SCHEMA);

    assertThat(typeNamed(schema, "Anonymous").getOwningModule()).isNull();
    assertThat(typeNamed(schema, "Unattributed").getOwningModule()).isNull();
  }

  private static Schema parse(String json) throws Exception {
    return Schema.initialize(
        new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "v1.0.0-beta.13");
  }

  private static Type typeNamed(Schema schema, String name) {
    return schema.getTypes().stream()
        .filter(type -> name.equals(type.getName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no type named " + name));
  }

  private static Field fieldNamed(Type type, String name) {
    return type.getFields().stream()
        .filter(field -> name.equals(field.getName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no field named " + name));
  }

  // The engine sends a directive argument JSON-encoded, so "client-dep" arrives with its quotes.
  private static final String SCHEMA =
      """
      {"__schema": {"queryType": {"name": "Query"}, "types": [
        {"kind": "OBJECT", "name": "Query", "fields": [
          {"name": "container", "type": {"kind": "OBJECT", "name": "Container"}, "args": []},
          {"name": "clientDep", "type": {"kind": "OBJECT", "name": "ClientDep"}, "args": [],
           "directives": [{"name": "sourceMap", "args": [{"name": "module", "value": "\\"client-dep\\""}]}]}
        ]},
        {"kind": "OBJECT", "name": "Container", "fields": []},
        {"kind": "OBJECT", "name": "ClientDep", "fields": [],
         "directives": [{"name": "sourceMap", "args": [{"name": "module", "value": "\\"client-dep\\""}]}]},
        {"kind": "OBJECT", "name": "Anonymous", "fields": [],
         "directives": [{"name": "sourceMap", "args": [{"name": "filename", "value": "\\"main.go\\""}]}]},
        {"kind": "OBJECT", "name": "Unattributed", "fields": [],
         "directives": [{"name": "sourceMap", "args": [{"name": "module", "value": "\\"\\""}]}]}
      ]}}
      """;
}
