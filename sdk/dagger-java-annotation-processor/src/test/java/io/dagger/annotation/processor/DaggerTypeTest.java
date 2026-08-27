package io.dagger.annotation.processor;

import static org.assertj.core.api.Assertions.assertThat;

import io.dagger.module.info.FieldInfo;
import io.dagger.module.info.TypeInfo;
import javax.lang.model.type.TypeKind;
import org.junit.jupiter.api.Test;

class DaggerTypeTest {

  @Test
  void optionalObjectReturnsAreRegisteredAsOptionalAndUnwrappedForSerialization() {
    DaggerType type = declared("java.util.Optional<io.dagger.core.Container>");

    assertThat(type.toDaggerTypeDef().toString())
        .isEqualTo(
            "io.dagger.core.Core.from(io.dagger.sdk.Dagger.dag()).typeDef().withObject(\"Container\").withOptional(true)");
    assertThat(type.toJavaType().toString())
        .isEqualTo("java.util.Optional<io.dagger.core.Container>");
    assertThat(type.valueForSerialization("result").toString()).isEqualTo("result.orElse(null)");
  }

  @Test
  void nonOptionalReturnsSerializeAsThemselves() {
    DaggerType type = declared("io.dagger.core.Container");

    assertThat(type.toDaggerTypeDef().toString())
        .isEqualTo(
            "io.dagger.core.Core.from(io.dagger.sdk.Dagger.dag()).typeDef().withObject(\"Container\")");
    assertThat(type.valueForSerialization("result").toString()).isEqualTo("result");
  }

  /**
   * A field keeps its declared type — unlike an argument, whose optionality the processor records
   * separately — so a public {@code Optional<X>} field is now registered as an optional field.
   */
  @Test
  void optionalObjectFieldsAreRegisteredAsOptional() {
    FieldInfo field =
        new FieldInfo(
            "maybeContainer",
            "",
            new TypeInfo("java.util.Optional<io.dagger.core.Container>", TypeKind.DECLARED.name()));

    assertThat(DaggerType.of(field.type()).toDaggerTypeDef().toString())
        .isEqualTo(
            "io.dagger.core.Core.from(io.dagger.sdk.Dagger.dag()).typeDef().withObject(\"Container\").withOptional(true)");
  }

  private static DaggerType declared(String typeName) {
    return DaggerType.of(new TypeInfo(typeName, TypeKind.DECLARED.name()));
  }
}
