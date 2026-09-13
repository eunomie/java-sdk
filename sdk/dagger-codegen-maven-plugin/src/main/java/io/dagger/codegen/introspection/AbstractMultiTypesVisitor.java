package io.dagger.codegen.introspection;

import com.palantir.javapoet.TypeSpec;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;

abstract class AbstractMultiTypesVisitor extends CodeWriter {

  private final Schema schema;
  private final TypeRegistry registry;

  public AbstractMultiTypesVisitor(
      Schema schema, TypeRegistry registry, Path targetDirectory, Charset encoding) {
    super(registry.targetPackage(), targetDirectory, encoding);
    this.schema = schema;
    this.registry = registry;
  }

  TypeRegistry registry() {
    return registry;
  }

  void visit(List<Type> types) throws IOException {
    TypeSpec typeSpec = generateType(types);
    write(typeSpec);
  }

  public Schema getSchema() {
    return schema;
  }

  abstract TypeSpec generateType(List<Type> types);
}
