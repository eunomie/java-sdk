package io.dagger.codegen.introspection;

import jakarta.json.bind.annotation.JsonbProperty;
import jakarta.json.bind.annotation.JsonbTransient;
import java.util.List;

public class Field {

  private String name;
  private String description;

  @JsonbProperty("type")
  private TypeRef typeRef;

  private List<InputObject> args;

  @JsonbProperty("isDeprecated")
  private boolean deprecated; // isDeprecated

  private String DeprecationReason;

  private List<Directive> directives;

  @JsonbTransient private List<InputObject> optionalArgs;

  private Type parentObject;

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = "<p>" + description.replace("\n", "<br/>") + "</p>";
  }

  public TypeRef getTypeRef() {
    return typeRef;
  }

  public void setTypeRef(TypeRef typeRef) {
    this.typeRef = typeRef;
  }

  public List<InputObject> getArgs() {
    return args;
  }

  public void setArgs(List<InputObject> args) {
    this.args = args;
  }

  public boolean isDeprecated() {
    return deprecated;
  }

  public void setDeprecated(boolean deprecated) {
    this.deprecated = deprecated;
  }

  public String getDeprecationReason() {
    return DeprecationReason;
  }

  public void setDeprecationReason(String deprecationReason) {
    DeprecationReason = deprecationReason;
  }

  public Type getParentObject() {
    return parentObject;
  }

  public void setParentObject(Type parentObject) {
    this.parentObject = parentObject;
  }

  public List<Directive> getDirectives() {
    return directives;
  }

  public void setDirectives(List<Directive> directives) {
    this.directives = directives;
  }

  /** Returns the @expectedType name for this field, if present. */
  public String getExpectedType() {
    return Directive.getExpectedType(directives);
  }

  /**
   * Returns the module that contributed this field, or null if it belongs to core. A module-owned
   * field on a core type (Query.hello, Binding.asHello) is how a module extends core.
   */
  public String getOwningModule() {
    return Directive.getSourceMapModule(directives);
  }

  boolean hasArgs() {
    return getArgs().size() > 0;
  }

  boolean hasOptionalArgs() {
    return getArgs().stream().anyMatch(Field::isArgOptional);
  }

  /** Returns the list of optional argument of this field */
  List<InputObject> getOptionalArgs() {
    if (optionalArgs == null) {
      optionalArgs = args.stream().filter(Field::isArgOptional).toList();
    }
    return optionalArgs;
  }

  List<InputObject> getRequiredArgs() {
    return args.stream().filter(arg -> !isArgOptional(arg)).toList();
  }

  /**
   * Whether the caller may omit this argument: a nullable type, or a non-null type carrying a
   * default the engine applies when it is absent. A defaulted non-null arg — how a module
   * constructor argument with {@code @Default} reaches the schema — is optional; treating it as
   * required would force every caller, including a module client's {@code from} factory, to pass
   * it.
   */
  private static boolean isArgOptional(InputObject arg) {
    return arg.getType().isOptional() || arg.getDefaultValue() != null;
  }

  @Override
  public String toString() {
    return "Field{"
        + "name='"
        + name
        + '\''
        + ", typeRef="
        + typeRef
        + ", args="
        + args
        + ", deprecated="
        + deprecated
        + ", optionalArgs="
        + optionalArgs
        + ", parentObject="
        + (parentObject != null ? parentObject.getName() : "null")
        + '}';
  }
}
