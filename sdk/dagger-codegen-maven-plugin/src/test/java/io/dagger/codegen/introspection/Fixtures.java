package io.dagger.codegen.introspection;

/** Introspection JSON shaped after the engine's real clientSchemaIntrospectionJSON. */
public final class Fixtures {

  private Fixtures() {}

  /**
   * A client schema for one module: core ({@code Query}, {@code Container}, {@code Binding}) plus
   * the module's root type, its constructor on {@code Query} (with one required and one optional
   * argument) and its accessor on {@code Binding}.
   */
  static String owned(String module) {
    return "\"directives\":[{\"name\":\"sourceMap\",\"args\":[{\"name\":\"module\",\"value\":\"\\\""
        + module
        + "\\\"\"}]}]";
  }

  /**
   * A client schema whose module contributes a {@code Query} field returning a core type and owns
   * nothing of its own: what the engine emits for a module named after a core type.
   */
  public static String coreNamedSchema(String module, String entry) {
    return "{\"__schema\":{\"queryType\":{\"name\":\"Query\"},\"types\":["
        + "{\"name\":\"String\",\"kind\":\"SCALAR\"},"
        + "{\"name\":\"Query\",\"kind\":\"OBJECT\",\"fields\":["
        + "  {\"name\":\""
        + entry
        + "\",\"args\":[],\"type\":{\"kind\":\"NON_NULL\",\"ofType\":{\"kind\":\"OBJECT\",\"name\":\"Container\"}},"
        + owned(module)
        + "}]},"
        + "{\"name\":\"Container\",\"kind\":\"OBJECT\",\"fields\":["
        + "  {\"name\":\"withExec\",\"args\":[],\"type\":{\"kind\":\"NON_NULL\",\"ofType\":{\"kind\":\"OBJECT\",\"name\":\"Container\"}}}]}"
        + "]}}";
  }

  /**
   * A client schema whose module adds the same field, taking an optional argument, to two core
   * types: two shims that would name one nested arguments class in the module's root class.
   */
  public static String twoShimsSchema(String module, String root, String entry, String shim) {
    String shimField =
        "  {\"name\":\""
            + shim
            + "\",\"args\":[{\"name\":\"tag\",\"type\":{\"kind\":\"SCALAR\",\"name\":\"String\"}}],"
            + "\"type\":{\"kind\":\"NON_NULL\",\"ofType\":{\"kind\":\"OBJECT\",\"name\":\""
            + root
            + "\"}},"
            + owned(module)
            + "}";
    return "{\"__schema\":{\"queryType\":{\"name\":\"Query\"},\"types\":["
        + "{\"name\":\"String\",\"kind\":\"SCALAR\"},"
        + "{\"name\":\"Query\",\"kind\":\"OBJECT\",\"fields\":["
        + "  {\"name\":\""
        + entry
        + "\",\"args\":[],\"type\":{\"kind\":\"NON_NULL\",\"ofType\":{\"kind\":\"OBJECT\",\"name\":\""
        + root
        + "\"}},"
        + owned(module)
        + "}]},"
        + "{\"name\":\"Container\",\"kind\":\"OBJECT\",\"fields\":["
        + shimField
        + "]},"
        + "{\"name\":\"Workspace\",\"kind\":\"OBJECT\",\"fields\":["
        + shimField
        + "]},"
        + "{\"name\":\""
        + root
        + "\",\"kind\":\"OBJECT\","
        + owned(module)
        + ",\"fields\":["
        + "  {\"name\":\"greet\",\"args\":[],\"type\":{\"kind\":\"SCALAR\",\"name\":\"String\"},"
        + owned(module)
        + "}]}"
        + "]}}";
  }

  public static String clientSchema(String module, String root, String entry, String binding) {
    return clientSchema(module, root, entry, binding, "name", "greeting");
  }

  /** The same schema with the entry point's required and optional argument named as asked. */
  public static String clientSchema(
      String module,
      String root,
      String entry,
      String binding,
      String requiredArg,
      String optionalArg) {
    return clientSchema(module, root, entry, binding, requiredArg, optionalArg, null);
  }

  /**
   * The same schema with the entry point's arguments named as asked; when {@code
   * requiredArgDefault} is non-null the otherwise-required arg carries that default value, which
   * makes it optional to the caller even though its type stays non-null.
   */
  public static String clientSchema(
      String module,
      String root,
      String entry,
      String binding,
      String requiredArg,
      String optionalArg,
      String requiredArgDefault) {
    String requiredArgDefaultJson =
        requiredArgDefault == null ? "" : ",\"defaultValue\":\"" + requiredArgDefault + "\"";
    return "{\"__schema\":{\"queryType\":{\"name\":\"Query\"},\"types\":["
        + "{\"name\":\"__Schema\",\"kind\":\"OBJECT\",\"fields\":[]},"
        + "{\"name\":\"String\",\"kind\":\"SCALAR\"},"
        + "{\"name\":\"ID\",\"kind\":\"SCALAR\"},"
        + "{\"name\":\"Query\",\"kind\":\"OBJECT\",\"fields\":["
        + "  {\"name\":\"container\",\"args\":[],\"type\":{\"kind\":\"NON_NULL\",\"ofType\":{\"kind\":\"OBJECT\",\"name\":\"Container\"}}},"
        + "  {\"name\":\""
        + entry
        + "\",\"args\":["
        + "    {\"name\":\""
        + requiredArg
        + "\",\"type\":{\"kind\":\"NON_NULL\",\"ofType\":{\"kind\":\"SCALAR\",\"name\":\"String\"}}"
        + requiredArgDefaultJson
        + "},"
        + "    {\"name\":\""
        + optionalArg
        + "\",\"type\":{\"kind\":\"SCALAR\",\"name\":\"String\"}}"
        + "  ],\"type\":{\"kind\":\"NON_NULL\",\"ofType\":{\"kind\":\"OBJECT\",\"name\":\""
        + root
        + "\"}},"
        + owned(module)
        + "}]},"
        + "{\"name\":\"Container\",\"kind\":\"OBJECT\",\"fields\":["
        + "  {\"name\":\"id\",\"args\":[],\"type\":{\"kind\":\"NON_NULL\",\"ofType\":{\"kind\":\"SCALAR\",\"name\":\"ID\"}}},"
        + "  {\"name\":\"withExec\",\"args\":[],\"type\":{\"kind\":\"NON_NULL\",\"ofType\":{\"kind\":\"OBJECT\",\"name\":\"Container\"}}}]},"
        + "{\"name\":\"Binding\",\"kind\":\"OBJECT\",\"fields\":["
        + "  {\"name\":\"asString\",\"args\":[],\"type\":{\"kind\":\"SCALAR\",\"name\":\"String\"}},"
        + "  {\"name\":\""
        + binding
        + "\",\"args\":[],\"type\":{\"kind\":\"OBJECT\",\"name\":\""
        + root
        + "\"},"
        + owned(module)
        + "}]},"
        + "{\"name\":\""
        + root
        + "\",\"kind\":\"OBJECT\","
        + owned(module)
        + ",\"fields\":["
        + "  {\"name\":\"greet\",\"args\":[],\"type\":{\"kind\":\"SCALAR\",\"name\":\"String\"},"
        + owned(module)
        + "}]}"
        + "]}}";
  }
}
