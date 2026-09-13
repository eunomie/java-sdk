package io.dagger.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

class ClientPomTest {

  @TempDir Path scope;

  @Test
  void aMinimalPomGainsTheProfile() throws Exception {
    Path pom = write(MINIMAL);

    ClientPom.insertInto(pom);

    Element profile = profile(pom, "dagger-clients");
    assertThat(text(profile, "activation/file/exists")).isEqualTo("dagger/src/main/java");
    assertThat(text(profile, "build/plugins/plugin/artifactId"))
        .isEqualTo("build-helper-maven-plugin");
    assertThat(text(profile, "build/plugins/plugin/version")).isEqualTo("3.3.0");
    assertThat(all(profile, "source")).containsExactly("dagger/src/main/java");
    // One source root and nothing else: the generated tree is all Java, so a
    // resource root would be a directory the SDK never writes.
    assertThat(all(profile, "goal")).containsExactly("add-source");
  }

  @Test
  void theProfileDeclaresTheSdkRuntimeDependenciesAtExplicitVersions() throws Exception {
    Path pom = write(MINIMAL);

    ClientPom.insertInto(pom);

    Element dependencies = child(profile(pom, "dagger-clients"), "dependencies");
    assertThat(coordinates(dependencies))
        .containsExactly(
            "jakarta.json:jakarta.json-api:2.1.3",
            "jakarta.json.bind:jakarta.json.bind-api:3.0.1",
            "org.slf4j:slf4j-api:2.0.17",
            "io.opentelemetry:opentelemetry-api:",
            "io.opentelemetry:opentelemetry-sdk:",
            "io.opentelemetry:opentelemetry-exporter-otlp:",
            "io.opentelemetry:opentelemetry-exporter-sender-jdk:",
            "org.eclipse:yasson:3.0.4");
    assertThat(all(profile(pom, "dagger-clients"), "exclusion"))
        .containsExactly("io.opentelemetryopentelemetry-exporter-sender-okhttp");
    assertThat(
            text(
                profile(pom, "dagger-clients"),
                "dependencyManagement/dependencies/dependency/version"))
        .isEqualTo("1.61.0");
  }

  /** slf4j-simple is the application's choice, not the SDK's. */
  @Test
  void theProfileDoesNotChooseALoggingImplementation() throws Exception {
    Path pom = write(MINIMAL);

    ClientPom.insertInto(pom);

    assertThat(Files.readString(pom)).doesNotContain("slf4j-simple");
  }

  @Test
  void insertingTwiceChangesNothingTheSecondTime() throws Exception {
    Path pom = write(MINIMAL);
    assertThat(ClientPom.insertInto(pom)).isTrue();
    byte[] once = Files.readAllBytes(pom);

    assertThat(ClientPom.insertInto(pom)).isFalse();

    assertThat(Files.readAllBytes(pom)).isEqualTo(once);
  }

  @Test
  void anUnrelatedProfileIsKept() throws Exception {
    Path pom = write(WITH_OTHER_PROFILE);

    ClientPom.insertInto(pom);

    assertThat(profileIds(pom)).containsExactly("release", "dagger-clients");
    assertThat(text(profile(pom, "release"), "properties/skipTests")).isEqualTo("true");
  }

  @Test
  void aProfileTheGoalWroteIsReplacedNotDuplicated() throws Exception {
    Path pom = write(MINIMAL);
    ClientPom.insertInto(pom);
    Files.writeString(pom, Files.readString(pom).replace("2.0.17", "1.7.36"));

    assertThat(ClientPom.insertInto(pom)).isTrue();

    assertThat(profileIds(pom)).containsExactly("dagger-clients");
    assertThat(Files.readString(pom)).contains("2.0.17").doesNotContain("1.7.36");
    assertThat(occurrences(Files.readString(pom), ClientPom.MARKER)).isEqualTo(1);
  }

  @Test
  void aProfileTheUserWroteIsRefused() throws Exception {
    Path pom = write(WITH_HAND_WRITTEN_PROFILE);
    String before = Files.readString(pom);

    assertThatThrownBy(() -> ClientPom.insertInto(pom))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(pom.toString())
        .hasMessageContaining("dagger-clients");

    assertThat(Files.readString(pom)).isEqualTo(before);
  }

  @Test
  void anXmlFileThatIsNotAPomIsRefused() throws Exception {
    Path settings = write("<settings><profiles/></settings>\n");
    String before = Files.readString(settings);

    assertThatThrownBy(() -> ClientPom.insertInto(settings))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not a Maven pom");

    assertThat(Files.readString(settings)).isEqualTo(before);
  }

  @Test
  void aPomWithNoProfilesElementGainsOne() throws Exception {
    Path pom = write(MINIMAL);

    ClientPom.insertInto(pom);

    assertThat(document(pom).getElementsByTagName("profiles").getLength()).isEqualTo(1);
    assertThat(profileIds(pom)).containsExactly("dagger-clients");
  }

  @Test
  void anEmptyProfilesElementIsFilledIn() throws Exception {
    Path pom = write(MINIMAL.replace("</project>", "  <profiles/>\n</project>"));

    ClientPom.insertInto(pom);

    assertThat(profileIds(pom)).containsExactly("dagger-clients");
    assertThat(document(pom).getElementsByTagName("profiles").getLength()).isEqualTo(1);
  }

  @Test
  void theRootElementsAttributesAreUntouched() throws Exception {
    Path pom = write(NAMESPACED);

    ClientPom.insertInto(pom);

    Element project = document(pom).getDocumentElement();
    assertThat(project.getAttribute("xmlns")).isEqualTo("http://maven.apache.org/POM/4.0.0");
    assertThat(project.getAttribute("xmlns:xsi"))
        .isEqualTo("http://www.w3.org/2001/XMLSchema-instance");
    assertThat(project.getAttribute("xsi:schemaLocation"))
        .isEqualTo("http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd");
    assertThat(Files.readString(pom)).startsWith(NAMESPACED.substring(0, NAMESPACED.indexOf('>')));
  }

  @Test
  void theUsersCommentsAreKept() throws Exception {
    Path pom = write(WITH_COMMENTS);

    ClientPom.insertInto(pom);

    assertThat(Files.readString(pom))
        .contains("<!-- keep me: the artifact id is load bearing -->")
        .contains("<!-- and me -->");
  }

  @Test
  void crlfLineEndingsAreNotRewritten() throws Exception {
    Path pom = write(MINIMAL.replace("\n", "\r\n"));

    ClientPom.insertInto(pom);

    String written = Files.readString(pom);
    assertThat(written).contains("\r\n");
    assertThat(written.replace("\r\n", "")).doesNotContain("\n");
  }

  @Test
  void theResultIsAPomMavenCanStillRead() throws Exception {
    Path pom = write(NAMESPACED);

    ClientPom.insertInto(pom);

    Document document = document(pom);
    assertThat(document.getDocumentElement().getNodeName()).isEqualTo("project");
    assertThat(text(document.getDocumentElement(), "modelVersion")).isEqualTo("4.0.0");
    assertThat(profileIds(pom)).containsExactly("dagger-clients");
    assertThat(profile(pom, "dagger-clients").getParentNode().getNodeName()).isEqualTo("profiles");
  }

  /** Maven reads a pom that starts with a byte-order mark, so this goal has to as well. */
  @Test
  void aPomWithAByteOrderMarkKeepsIt() throws Exception {
    Path pom = write("\uFEFF" + MINIMAL);

    assertThat(ClientPom.insertInto(pom)).isTrue();

    assertThat(Files.readAllBytes(pom)).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
    assertThat(profileIds(pom)).containsExactly("dagger-clients");
  }

  @Test
  void theUsersFileModeIsKept() throws Exception {
    Path pom = write(MINIMAL);
    Files.setPosixFilePermissions(pom, PosixFilePermissions.fromString("rw-r-----"));

    ClientPom.insertInto(pom);

    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(pom)))
        .isEqualTo("rw-r-----");
  }

  private Path write(String pom) throws Exception {
    Path path = scope.resolve("pom.xml");
    Files.write(path, pom.getBytes(StandardCharsets.UTF_8));
    return path;
  }

  private static Document document(Path pom) throws Exception {
    return DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(new ByteArrayInputStream(Files.readAllBytes(pom)));
  }

  private static List<String> profileIds(Path pom) throws Exception {
    NodeList profiles = document(pom).getElementsByTagName("profile");
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < profiles.getLength(); i++) {
      ids.add(text((Element) profiles.item(i), "id"));
    }
    return ids;
  }

  private static Element profile(Path pom, String id) throws Exception {
    NodeList profiles = document(pom).getElementsByTagName("profile");
    for (int i = 0; i < profiles.getLength(); i++) {
      Element profile = (Element) profiles.item(i);
      if (id.equals(text(profile, "id"))) {
        return profile;
      }
    }
    throw new AssertionError("no profile with the id " + id);
  }

  private static Element child(Element parent, String name) {
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node node = children.item(i);
      if (node.getNodeType() == Node.ELEMENT_NODE && node.getNodeName().equals(name)) {
        return (Element) node;
      }
    }
    throw new AssertionError("no child element " + name);
  }

  private static String text(Element parent, String path) {
    Element element = parent;
    for (String name : path.split("/")) {
      element = child(element, name);
    }
    return element.getTextContent().trim();
  }

  private static List<String> all(Element parent, String name) {
    NodeList nodes = parent.getElementsByTagName(name);
    List<String> values = new ArrayList<>();
    for (int i = 0; i < nodes.getLength(); i++) {
      values.add(nodes.item(i).getTextContent().trim().replaceAll("\\s+", ""));
    }
    return values;
  }

  private static List<String> coordinates(Element dependencies) {
    NodeList nodes = dependencies.getElementsByTagName("dependency");
    List<String> coordinates = new ArrayList<>();
    for (int i = 0; i < nodes.getLength(); i++) {
      Element dependency = (Element) nodes.item(i);
      coordinates.add(
          text(dependency, "groupId")
              + ":"
              + text(dependency, "artifactId")
              + ":"
              + optional(dependency, "version"));
    }
    return coordinates;
  }

  private static String optional(Element parent, String name) {
    NodeList nodes = parent.getElementsByTagName(name);
    return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
  }

  private static int occurrences(String text, String needle) {
    int count = 0;
    for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
      count++;
    }
    return count;
  }

  private static final String MINIMAL =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <project>
        <modelVersion>4.0.0</modelVersion>
        <groupId>com.example</groupId>
        <artifactId>app</artifactId>
        <version>1.0-SNAPSHOT</version>
      </project>
      """;

  private static final String NAMESPACED =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <project xmlns="http://maven.apache.org/POM/4.0.0"
               xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
               xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>app</artifactId>
          <version>1.0-SNAPSHOT</version>
      </project>
      """;

  private static final String WITH_OTHER_PROFILE =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <project>
        <modelVersion>4.0.0</modelVersion>
        <groupId>com.example</groupId>
        <artifactId>app</artifactId>
        <version>1.0-SNAPSHOT</version>
        <profiles>
          <profile>
            <id>release</id>
            <properties>
              <skipTests>true</skipTests>
            </properties>
          </profile>
        </profiles>
      </project>
      """;

  private static final String WITH_HAND_WRITTEN_PROFILE =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <project>
        <modelVersion>4.0.0</modelVersion>
        <groupId>com.example</groupId>
        <artifactId>app</artifactId>
        <version>1.0-SNAPSHOT</version>
        <profiles>
          <profile>
            <id>dagger-clients</id>
            <properties>
              <mine>yes</mine>
            </properties>
          </profile>
        </profiles>
      </project>
      """;

  private static final String WITH_COMMENTS =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <project>
        <modelVersion>4.0.0</modelVersion>
        <groupId>com.example</groupId>
        <!-- keep me: the artifact id is load bearing -->
        <artifactId>app</artifactId>
        <version>1.0-SNAPSHOT</version>
        <profiles>
          <!-- and me -->
          <profile>
            <id>release</id>
          </profile>
        </profiles>
      </project>
      """;
}
