package io.dagger.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.util.List;
import org.junit.jupiter.api.Test;

class ModulePackageTest {

  @Test
  void aDaggerNameBecomesALowercaseSegment() {
    assertThat(ModulePackage.segmentFor("sdk-helpers")).isEqualTo("sdkhelpers");
    assertThat(ModulePackage.segmentFor("clientDep")).isEqualTo("clientdep");
    assertThat(ModulePackage.segmentFor("my_module2")).isEqualTo("mymodule2");
  }

  @Test
  void everyTargetGetsItsOwnPackageUnderTheOneRoot() {
    assertThat(ModulePackage.packagesFor(List.of("alpha", "sdk-helpers")))
        .containsExactly(
            entry("alpha", "io.dagger.client.modules.alpha"),
            entry("sdk-helpers", "io.dagger.client.modules.sdkhelpers"));
  }

  @Test
  void twoNamesThatNormalizeToOneSegmentAreRefused() {
    assertThatThrownBy(() -> ModulePackage.packagesFor(List.of("foo-bar", "foo_bar")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("foo-bar")
        .hasMessageContaining("foo_bar")
        .hasMessageContaining("io.dagger.client.modules.foobar");
  }

  /** A package directory written to a case-insensitive filesystem separates no better than this. */
  @Test
  void segmentsThatDifferOnlyByCaseAreRefused() {
    assertThatThrownBy(() -> ModulePackage.packagesFor(List.of("Alpha", "alpha")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theSameModuleListedTwiceIsNotACollision() {
    assertThat(ModulePackage.packagesFor(List.of("alpha", "alpha"))).hasSize(1);
  }

  @Test
  void aNameWithNoUsableCharactersIsRefused() {
    assertThatThrownBy(() -> ModulePackage.segmentFor("--"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("leading ASCII letter");
    assertThatThrownBy(() -> ModulePackage.segmentFor("2fast"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("leading ASCII letter");
  }

  @Test
  void aNameJavaReservesIsRefused() {
    assertThatThrownBy(() -> ModulePackage.segmentFor("package"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reserves");
    assertThatThrownBy(() -> ModulePackage.segmentFor("record"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reserves");
  }
}
