package io.dagger.codegen.introspection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Picks one core schema out of the several a standalone scope has, and refuses a target that
 * disagrees with the core it is generated next to.
 *
 * <p>A scope with no module of its own has no module-facing schema. What it has is one
 * client-facing schema per target, and each of those carries a full copy of core plus only that
 * target's contributions to it. Core is therefore the part they agree on; every target's
 * contributions are emitted in that target's own package, so none of them belongs here.
 *
 * <p>The agreement is checked rather than assumed. The engine renders core through the declared
 * engine version of the module it was asked about, so two targets on different versions produce two
 * different cores, and silently taking one of them would generate code against a core the other
 * target was never compiled for.
 */
public final class SchemaMerge {

  private SchemaMerge() {}

  /**
   * Check that every schema holds the same core, and return the first.
   *
   * <p>The result does not depend on the order: the schemas have to agree on core for any of them
   * to be returned, and what they do not agree on — each target's own contributions — is stripped
   * out again when core is partitioned.
   *
   * @param byTarget each target's client-facing schema, under the name it generates as
   */
  public static Schema core(LinkedHashMap<String, Schema> byTarget) {
    if (byTarget.isEmpty()) {
      throw new IllegalArgumentException(
          "a standalone scope needs at least one target to take core from");
    }
    List<Map.Entry<String, Schema>> targets = List.copyOf(byTarget.entrySet());
    Map.Entry<String, Schema> base = targets.get(0);
    Map<String, TypeShape> baseShape = bareCore(base.getValue());
    for (Map.Entry<String, Schema> other : targets.subList(1, targets.size())) {
      String difference =
          firstDifference(
              base.getKey(), baseShape, other.getKey(), bareCore(other.getValue()), false);
      if (difference != null) {
        throw skewed(base, other, difference);
      }
    }
    return base.getValue();
  }

  /**
   * Refuse a target whose core disagrees with the module scope's own.
   *
   * <p>The scope's schema is module-facing and the target's is client-facing, and the engine
   * deliberately hides part of core from module code: measured against a v1.0.0-beta.13 engine, a
   * client-facing core carries five types and six fields a module-facing one does not. So the rule
   * is coverage, not equality — everything the module can see the target must have, with the same
   * shape, and the target may have more.
   */
  public static void requireCoreCovers(String target, Schema moduleSchema, Schema targetSchema) {
    String scope = "the module scope";
    String difference =
        firstDifference(scope, bareCore(moduleSchema), target, bareCore(targetSchema), true);
    if (difference != null) {
      throw skewed(Map.entry(scope, moduleSchema), Map.entry(target, targetSchema), difference);
    }
  }

  private static IllegalArgumentException skewed(
      Map.Entry<String, Schema> left, Map.Entry<String, Schema> right, String difference) {
    return new IllegalArgumentException(
        String.format(
            "%s (engine %s) and %s (engine %s) do not agree on the core API: %s."
                + " The engine renders core through each module's declared engine version, so"
                + " modules pinned to different ones cannot share generated bindings.",
            left.getKey(),
            left.getValue().getVersion(),
            right.getKey(),
            right.getValue().getVersion(),
            difference));
  }

  /** One core field, as far as generated Java can tell it apart from another. */
  private record FieldShape(String type, Map<String, String> args) {}

  private record TypeShape(TypeKind kind, Map<String, FieldShape> fields) {}

  /**
   * A schema's core, with every contribution any module makes to it removed.
   *
   * <p>The shape is what the generator turns into Java signatures: a type's kind, and per field its
   * rendered return type and the rendered type of every argument. Enum values, descriptions and
   * deprecations are left out — they change the generated code's documentation, not what it
   * compiles against, and an engine that skews them without skewing a signature is not the failure
   * this guards.
   */
  private static Map<String, TypeShape> bareCore(Schema schema) {
    Map<String, TypeShape> shapes = new LinkedHashMap<>();
    for (Type type : schema.getTypes()) {
      if (type.getOwningModule() != null) {
        continue;
      }
      Map<String, FieldShape> fields = new LinkedHashMap<>();
      if (type.getFields() != null) {
        for (Field field : type.getFields()) {
          if (field.getOwningModule() != null) {
            continue;
          }
          Map<String, String> args = new LinkedHashMap<>();
          if (field.getArgs() != null) {
            field.getArgs().forEach(arg -> args.put(arg.getName(), render(arg.getType())));
          }
          fields.put(field.getName(), new FieldShape(render(field.getTypeRef()), args));
        }
      }
      shapes.put(type.getName(), new TypeShape(type.getKind(), fields));
    }
    return shapes;
  }

  /** A type reference as GraphQL writes it, so nullability and list nesting both count. */
  private static String render(TypeRef ref) {
    if (ref == null) {
      return "?";
    }
    return switch (ref.getKind()) {
      case NON_NULL -> render(ref.getOfType()) + "!";
      case LIST -> "[" + render(ref.getOfType()) + "]";
      default -> ref.getName();
    };
  }

  /**
   * What first tells two cores apart, in words, or null when they agree.
   *
   * @param wider whether the right core is allowed to hold types and fields the left does not
   */
  private static String firstDifference(
      String leftName,
      Map<String, TypeShape> left,
      String rightName,
      Map<String, TypeShape> right,
      boolean wider) {
    for (Map.Entry<String, TypeShape> entry : left.entrySet()) {
      String name = entry.getKey();
      TypeShape here = entry.getValue();
      TypeShape there = right.get(name);
      if (there == null) {
        return String.format("%s has no type %s", rightName, name);
      }
      if (here.kind() != there.kind()) {
        return String.format(
            "type %s is a %s in %s and a %s in %s",
            name, here.kind(), leftName, there.kind(), rightName);
      }
      String field = firstFieldDifference(name, leftName, here.fields(), rightName, there.fields());
      if (field != null) {
        return field;
      }
      if (!wider) {
        String extra = firstMissing(there.fields().keySet(), here.fields().keySet());
        if (extra != null) {
          return String.format("%s has no field %s.%s", leftName, name, extra);
        }
      }
    }
    if (!wider) {
      String extra = firstMissing(right.keySet(), left.keySet());
      if (extra != null) {
        return String.format("%s has no type %s", leftName, extra);
      }
    }
    return null;
  }

  private static String firstFieldDifference(
      String type,
      String leftName,
      Map<String, FieldShape> left,
      String rightName,
      Map<String, FieldShape> right) {
    for (Map.Entry<String, FieldShape> entry : left.entrySet()) {
      String name = entry.getKey();
      FieldShape here = entry.getValue();
      FieldShape there = right.get(name);
      if (there == null) {
        return String.format("%s has no field %s.%s", rightName, type, name);
      }
      if (!here.type().equals(there.type())) {
        return String.format(
            "%s.%s returns %s in %s and %s in %s",
            type, name, here.type(), leftName, there.type(), rightName);
      }
      if (!here.args().equals(there.args())) {
        return String.format(
            "%s.%s takes %s in %s and %s in %s",
            type, name, here.args(), leftName, there.args(), rightName);
      }
    }
    return null;
  }

  private static String firstMissing(Set<String> names, Set<String> from) {
    return names.stream().filter(name -> !from.contains(name)).findFirst().orElse(null);
  }
}
