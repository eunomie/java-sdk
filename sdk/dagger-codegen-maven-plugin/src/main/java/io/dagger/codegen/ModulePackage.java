package io.dagger.codegen;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.lang.model.SourceVersion;

/**
 * The Java package a module's bindings are generated into.
 *
 * <p>A module name is a Dagger name, so it can hold characters a Java package segment cannot:
 * {@code sdk-helpers} becomes {@code sdkhelpers}. Distinct module names can normalize to the same
 * segment, which would make one module's bindings overwrite another's, so the mapping is computed
 * for a whole target set at once and refuses a set it cannot separate.
 *
 * <p>One segment is spoken for before any target asks: core is generated under this root too, so
 * {@value #CORE_SEGMENT} is refused whatever the target set.
 */
public final class ModulePackage {

  /** The package every module's bindings go under. */
  public static final String ROOT = "io.dagger.client.modules";

  /** The segment the generated core API takes, which is why no module may take it. */
  public static final String CORE_SEGMENT = "core";

  private ModulePackage() {}

  /**
   * Map each module name to its fully qualified package, refusing a set that cannot be separated.
   *
   * <p>The comparison that decides separation is case-insensitive, because a case-sensitive
   * filesystem is not the only kind these packages are written to.
   */
  public static Map<String, String> packagesFor(List<String> moduleNames) {
    Map<String, String> packages = new LinkedHashMap<>();
    Map<String, String> claimedBy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (String moduleName : moduleNames) {
      String segment = segmentFor(moduleName);
      String previous = claimedBy.putIfAbsent(segment, moduleName);
      if (previous != null && !previous.equals(moduleName)) {
        throw new IllegalArgumentException(
            String.format(
                "modules %s and %s both generate into %s.%s; rename or alias one of them",
                previous, moduleName, ROOT, segment));
      }
      packages.put(moduleName, ROOT + "." + segment);
    }
    return packages;
  }

  /** The package segment for one module name. */
  public static String segmentFor(String moduleName) {
    StringBuilder segment = new StringBuilder(moduleName.length());
    for (int i = 0; i < moduleName.length(); i++) {
      char c = moduleName.charAt(i);
      if (Character.isLetterOrDigit(c) && c < 128) {
        segment.append(Character.toLowerCase(c));
      }
    }
    String candidate = segment.toString();
    if (candidate.isEmpty() || Character.isDigit(candidate.charAt(0))) {
      throw new IllegalArgumentException(
          String.format(
              "module %s does not name a Java package segment; a segment needs a leading ASCII"
                  + " letter",
              moduleName));
    }
    if (RESERVED.contains(candidate) || !SourceVersion.isName(candidate)) {
      throw new IllegalArgumentException(
          String.format("module %s normalizes to %s, which Java reserves", moduleName, candidate));
    }
    if (CORE_SEGMENT.equals(candidate)) {
      throw new IllegalArgumentException(
          String.format(
              "module %s normalizes to %s, where the generated core API is emitted; core is a"
                  + " client package like any other and %s.%s is taken. Rename or alias the"
                  + " module.",
              moduleName, candidate, ROOT, CORE_SEGMENT));
    }
    return candidate;
  }

  // isName rejects keywords but not these, which are legal identifiers a package segment may not
  // be.
  private static final Set<String> RESERVED = Set.of("var", "yield", "record", "sealed", "permits");
}
