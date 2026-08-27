package io.dagger.clients.depclient;

import static io.dagger.client.dep.Dep.dep;

import io.dagger.sdk.Session;
import io.dagger.sdk.Dagger;

/** A standalone client: opens its own session and reaches the module through the preamble. */
public class Main {
  public static void main(String[] args) throws Exception {
    try (Session dag = Dagger.connect()) {
      System.out.println(dep(dag).greet("client"));
    }
  }
}
