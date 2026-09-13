package io.dagger.codegen.introspection;

import static org.assertj.core.api.Assertions.assertThat;

import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.TypeSpec;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class OptionalArgsCodegenTest {

  /**
   * A module SDK that hands a default value to the engine gets the argument declared non-null with
   * that default, and the engine fills it in when a caller omits it. It is as optional as a
   * nullable one, so it belongs in the optional arguments, not in the method's parameters.
   */
  @Test
  void argumentsWithADefaultValueAreOptional() throws Exception {
    String generated =
        generateQuery(
            field(
                "clientDefaults",
                arg("name", nonNull(scalar("String")), "\"world\""),
                arg("times", nonNull(scalar("Int")), "1"),
                arg("suffix", scalar("String"), null)));

    assertThat(generated)
        .contains("public ClientDefaults clientDefaults() {")
        .contains("public ClientDefaults clientDefaults(ClientDefaultsArguments optArgs) {")
        .contains("public ClientDefaultsArguments withName(String name)")
        .contains("public ClientDefaultsArguments withTimes(Integer times)")
        .contains("public ClientDefaultsArguments withSuffix(String suffix)")
        .doesNotContain("builder.add(\"name\", name)")
        .doesNotContain("builder.add(\"times\", times)");
  }

  @Test
  void argumentsWithoutADefaultValueKeepTheirShape() throws Exception {
    String generated =
        generateQuery(
            field(
                "clientDep",
                arg("name", nonNull(scalar("String")), null),
                arg("suffix", scalar("String"), null)));

    assertThat(generated)
        .contains("public ClientDep clientDep(String name) {")
        .contains("public ClientDep clientDep(String name, ClientDepArguments optArgs) {")
        .contains("builder.add(\"name\", name)")
        .contains("public ClientDepArguments withSuffix(String suffix)")
        .doesNotContain("withName(");
  }

  private static String generateQuery(Field field) throws Exception {
    Type query = new Type();
    query.setKind(TypeKind.OBJECT);
    query.setName("Query");
    query.setDescription("");
    query.setInterfaces(List.of());
    field.setParentObject(query);
    query.setFields(List.of(field));

    byte[] introspection = "{\"__schema\":{\"types\":[]}}".getBytes(StandardCharsets.UTF_8);
    Schema schema = Schema.initialize(new ByteArrayInputStream(introspection), "v1.0.0-beta.11");
    TypeSpec client =
        new ObjectVisitor(
                schema,
                TypeRegistry.singlePackage("io.dagger.client"),
                Path.of("."),
                StandardCharsets.UTF_8)
            .generateType(query);
    return JavaFile.builder("io.dagger.client", client).build().toString();
  }

  private static Field field(String name, InputObject... args) {
    String typeName = Character.toUpperCase(name.charAt(0)) + name.substring(1);
    Field field = new Field();
    field.setName(name);
    field.setDescription("");
    field.setTypeRef(nonNull(object(typeName)));
    field.setArgs(List.of(args));
    field.setDirectives(List.of());
    return field;
  }

  private static InputObject arg(String name, TypeRef type, String defaultValue) {
    InputObject arg = new InputObject();
    arg.setName(name);
    arg.setDescription("");
    arg.setType(type);
    arg.setDefaultValue(defaultValue);
    arg.setDirectives(List.of());
    return arg;
  }

  private static TypeRef scalar(String name) {
    TypeRef ref = new TypeRef();
    ref.setKind(TypeKind.SCALAR);
    ref.setName(name);
    return ref;
  }

  private static TypeRef object(String name) {
    TypeRef ref = new TypeRef();
    ref.setKind(TypeKind.OBJECT);
    ref.setName(name);
    return ref;
  }

  private static TypeRef nonNull(TypeRef inner) {
    TypeRef ref = new TypeRef();
    ref.setKind(TypeKind.NON_NULL);
    ref.setOfType(inner);
    return ref;
  }
}
