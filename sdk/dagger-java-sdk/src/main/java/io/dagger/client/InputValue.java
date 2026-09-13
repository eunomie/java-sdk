package io.dagger.client;

import java.util.Map;

/** A GraphQL input object, as generated input types implement it from their own package. */
public interface InputValue {
  Map<String, Object> toMap();
}
