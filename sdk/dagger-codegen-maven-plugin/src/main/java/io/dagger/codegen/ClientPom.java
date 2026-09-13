package io.dagger.codegen;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Registers the generated Dagger client sources with a Maven project the SDK does not own, by
 * writing a single marked {@code <profile>} into its {@code pom.xml}.
 *
 * <p>The edit is a text splice over the original bytes rather than a re-serialization of a parsed
 * document, because everything outside the profile belongs to the user: comments, attribute layout,
 * entity references and line endings all have to come out as they went in.
 */
final class ClientPom {

  static final String PROFILE_ID = "dagger-clients";

  /** Identifies a profile as this goal's own output, and so as safe to replace. */
  static final String MARKER = "dagger-codegen-maven-plugin:client-pom";

  private static final String BOM = "\uFEFF";

  private ClientPom() {}

  /**
   * Inserts, or replaces, the {@code dagger-clients} profile of the given {@code pom.xml}.
   *
   * @return whether the file changed
   * @throws IllegalStateException if the pom already declares a {@code dagger-clients} profile that
   *     this goal did not write
   */
  static boolean insertInto(Path pom) throws IOException {
    String raw = Files.readString(pom, StandardCharsets.UTF_8);
    // Maven reads a pom that starts with a byte-order mark; the XML parser will not, and the mark
    // belongs to the user's file either way, so it comes off for the splice and goes back after.
    boolean byteOrderMark = raw.startsWith(BOM);
    String text = byteOrderMark ? raw.substring(BOM.length()) : raw;
    Scan scan;
    try {
      scan = scan(text);
    } catch (XMLStreamException e) {
      throw new IOException(pom + " is not well-formed XML: " + e.getMessage(), e);
    }
    if (scan.unmarkedProfile) {
      throw new IllegalStateException(
          "%s already declares a profile with the id %s that the Dagger Java SDK did not write; rename or remove it, then generate again"
              .formatted(pom, PROFILE_ID));
    }
    String updated = splice(text, scan);
    if (updated.equals(text)) {
      return false;
    }
    write(pom, byteOrderMark ? BOM + updated : updated);
    return true;
  }

  /**
   * Replace the pom in one step. It is the user's file, and an interrupted write would leave them a
   * truncated one.
   */
  private static void write(Path pom, String content) throws IOException {
    Path directory = pom.toAbsolutePath().getParent();
    Path temporary = Files.createTempFile(directory, pom.getFileName().toString(), ".dagger");
    try {
      Files.writeString(temporary, content, StandardCharsets.UTF_8);
      copyPermissions(pom, temporary);
      try {
        Files.move(temporary, pom, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temporary, pom, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException | RuntimeException e) {
      Files.deleteIfExists(temporary);
      throw e;
    }
  }

  private static void copyPermissions(Path from, Path to) throws IOException {
    if (Files.getFileAttributeView(to, PosixFileAttributeView.class) == null) {
      return;
    }
    Files.setPosixFilePermissions(to, Files.getPosixFilePermissions(from));
  }

  private static String splice(String text, Scan scan) {
    String nl = text.contains("\r\n") ? "\r\n" : "\n";
    String unit = indentUnit(text, scan);

    if (scan.markedProfileStart >= 0) {
      String at = lineIndent(text, scan.markedProfileStart);
      return text.substring(0, scan.markedProfileStart)
          + profile(at, unit, nl)
          + text.substring(scan.markedProfileEnd);
    }

    if (scan.profilesStart < 0) {
      String base = unit;
      String child = unit + unit;
      int at = whitespaceStart(text, scan.projectEndTagStart);
      return text.substring(0, at)
          + nl
          + nl
          + base
          + "<profiles>"
          + nl
          + child
          + profile(child, unit, nl)
          + nl
          + base
          + "</profiles>"
          + (at == scan.projectEndTagStart ? nl : "")
          + text.substring(at);
    }

    String base = lineIndent(text, scan.profilesStart);
    String child =
        scan.firstProfileStart >= 0 ? lineIndent(text, scan.firstProfileStart) : base + unit;

    if (scan.profilesSelfClosed) {
      return text.substring(0, scan.profilesStart)
          + "<profiles>"
          + nl
          + child
          + profile(child, unit, nl)
          + nl
          + base
          + "</profiles>"
          + text.substring(scan.profilesEnd);
    }

    int at = whitespaceStart(text, scan.profilesEndTagStart);
    boolean ownLine = text.substring(at, scan.profilesEndTagStart).indexOf('\n') >= 0;
    return text.substring(0, at)
        + nl
        + child
        + profile(child, unit, nl)
        + (ownLine ? "" : nl + base)
        + text.substring(at);
  }

  /** Re-indents {@link #PROFILE} onto {@code at}, one {@code unit} per level of the template. */
  private static String profile(String at, String unit, String nl) {
    StringBuilder out = new StringBuilder();
    String[] lines = PROFILE.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      if (i > 0) {
        out.append(nl).append(at);
      }
      String line = lines[i];
      int depth = (line.length() - line.stripLeading().length()) / 4;
      out.append(unit.repeat(depth)).append(line.stripLeading());
    }
    return out.toString();
  }

  private static String indentUnit(String text, Scan scan) {
    int firstChild = text.indexOf('<', scan.projectTagEnd);
    if (firstChild < 0) {
      return DEFAULT_INDENT;
    }
    String indent = lineIndent(text, firstChild);
    return indent.isEmpty() ? DEFAULT_INDENT : indent;
  }

  /** The whitespace {@code pos} is indented by, or the empty string if code precedes it. */
  private static String lineIndent(String text, int pos) {
    String prefix = text.substring(text.lastIndexOf('\n', pos - 1) + 1, pos);
    return prefix.isBlank() ? prefix : "";
  }

  private static int whitespaceStart(String text, int pos) {
    int at = pos;
    while (at > 0 && Character.isWhitespace(text.charAt(at - 1))) {
      at--;
    }
    return at;
  }

  private static final class Scan {
    int projectTagEnd;
    int projectEndTagStart = -1;
    int profilesStart = -1;
    int profilesEnd = -1;
    int profilesEndTagStart = -1;
    boolean profilesSelfClosed;
    int firstProfileStart = -1;
    int markedProfileStart = -1;
    int markedProfileEnd = -1;
    boolean unmarkedProfile;
  }

  /**
   * Locates the profiles of a pom in its source text.
   *
   * <p>The offsets a {@link XMLStreamReader} reports are only approximate — its scanner runs ahead
   * of the event it is reporting — so the spans are walked out instead: markup is the only thing a
   * {@code '<'} can start, so every event's position is the next {@code '<'} after the end of the
   * one before it, and character data never has to be measured at all.
   */
  private static Scan scan(String text) throws XMLStreamException {
    Scan scan = new Scan();
    XMLInputFactory factory = XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(text));

    // The XML declaration is the one piece of markup the event stream never reports.
    int cursor = text.startsWith("<?xml") ? text.indexOf("?>") + 2 : 0;
    int depth = 0;
    int startTag = -1;
    boolean selfClosed = false;
    int profileStart = -1;
    int commentStart = -1;
    boolean commentIsMarker = false;
    StringBuilder id = null;
    String profileId = null;

    while (reader.hasNext()) {
      int event = reader.next();
      switch (event) {
        case XMLStreamConstants.COMMENT -> {
          int start = text.indexOf("<!--", cursor);
          cursor = text.indexOf("-->", start) + 3;
          if (depth == 2) {
            commentStart = start;
            commentIsMarker = reader.getText().contains(MARKER);
          }
        }
        case XMLStreamConstants.CDATA -> cursor = text.indexOf("]]>", cursor) + 3;
        case XMLStreamConstants.PROCESSING_INSTRUCTION ->
            cursor = text.indexOf("?>", text.indexOf('<', cursor)) + 2;
        case XMLStreamConstants.CHARACTERS, XMLStreamConstants.SPACE -> {
          if (id != null) {
            id.append(reader.getText());
          }
        }
        case XMLStreamConstants.START_ELEMENT -> {
          depth++;
          startTag = text.indexOf('<', cursor);
          cursor = startTagEnd(text, startTag);
          selfClosed = text.charAt(cursor - 2) == '/';
          String name = reader.getLocalName();
          if (depth == 1) {
            if (!name.equals("project")) {
              // This writes into a file the SDK does not own; splicing a <profiles> into
              // something that is not a pom would be a silent, destructive mistake.
              throw new IllegalArgumentException(
                  "the root element is <" + name + ">, not <project>; this is not a Maven pom");
            }
            scan.projectTagEnd = cursor;
          } else if (depth == 2 && name.equals("profiles")) {
            scan.profilesStart = startTag;
            scan.profilesSelfClosed = selfClosed;
          } else if (depth == 3 && name.equals("profile") && scan.profilesStart >= 0) {
            profileStart = startTag;
            if (scan.firstProfileStart < 0) {
              scan.firstProfileStart = startTag;
            }
          } else if (depth == 4 && name.equals("id") && profileStart >= 0 && profileId == null) {
            id = new StringBuilder();
          }
        }
        case XMLStreamConstants.END_ELEMENT -> {
          int start = startTag;
          if (!selfClosed) {
            start = text.indexOf('<', cursor);
            cursor = text.indexOf('>', start) + 1;
          }
          selfClosed = false;
          String name = reader.getLocalName();
          if (depth == 4 && name.equals("id") && id != null) {
            profileId = id.toString().trim();
            id = null;
          } else if (depth == 3 && name.equals("profile") && profileStart >= 0) {
            if (PROFILE_ID.equals(profileId)) {
              if (commentIsMarker) {
                scan.markedProfileStart = commentStart;
                scan.markedProfileEnd = cursor;
              } else {
                scan.unmarkedProfile = true;
              }
            }
            profileStart = -1;
            profileId = null;
            id = null;
            commentStart = -1;
            commentIsMarker = false;
          } else if (depth == 2 && name.equals("profiles")) {
            scan.profilesEndTagStart = start;
            scan.profilesEnd = cursor;
          } else if (depth == 1) {
            scan.projectEndTagStart = start;
          }
          depth--;
        }
        default -> {}
      }
    }
    return scan;
  }

  /** The offset just past a start tag, whose attribute values may hold a {@code '>'}. */
  private static int startTagEnd(String text, int start) {
    char quote = 0;
    for (int at = start; at < text.length(); at++) {
      char c = text.charAt(at);
      if (quote != 0) {
        if (c == quote) {
          quote = 0;
        }
      } else if (c == '"' || c == '\'') {
        quote = c;
      } else if (c == '>') {
        return at + 1;
      }
    }
    throw new IllegalStateException("unterminated start tag at " + start);
  }

  private static final String DEFAULT_INDENT = "    ";

  private static final String PROFILE =
      """
      <!--
      Generated by the Dagger Java SDK (dagger-codegen-maven-plugin:client-pom).
      It compiles and packages the Dagger client sources under dagger/, and is
      active only while they are there. Delete it to drop the integration;
      `dagger generate` writes it back.
      -->
      <profile>
          <id>dagger-clients</id>
          <activation>
              <file>
                  <exists>dagger/src/main/java</exists>
              </file>
          </activation>
          <dependencyManagement>
              <dependencies>
                  <dependency>
                      <groupId>io.opentelemetry</groupId>
                      <artifactId>opentelemetry-bom</artifactId>
                      <version>1.61.0</version>
                      <type>pom</type>
                      <scope>import</scope>
                  </dependency>
              </dependencies>
          </dependencyManagement>
          <dependencies>
              <dependency>
                  <groupId>jakarta.json</groupId>
                  <artifactId>jakarta.json-api</artifactId>
                  <version>2.1.3</version>
              </dependency>
              <dependency>
                  <groupId>jakarta.json.bind</groupId>
                  <artifactId>jakarta.json.bind-api</artifactId>
                  <version>3.0.1</version>
              </dependency>
              <dependency>
                  <groupId>org.slf4j</groupId>
                  <artifactId>slf4j-api</artifactId>
                  <version>2.0.17</version>
              </dependency>
              <dependency>
                  <groupId>io.opentelemetry</groupId>
                  <artifactId>opentelemetry-api</artifactId>
              </dependency>
              <dependency>
                  <groupId>io.opentelemetry</groupId>
                  <artifactId>opentelemetry-sdk</artifactId>
              </dependency>
              <dependency>
                  <groupId>io.opentelemetry</groupId>
                  <artifactId>opentelemetry-exporter-otlp</artifactId>
                  <exclusions>
                      <exclusion>
                          <groupId>io.opentelemetry</groupId>
                          <artifactId>opentelemetry-exporter-sender-okhttp</artifactId>
                      </exclusion>
                  </exclusions>
              </dependency>
              <dependency>
                  <groupId>io.opentelemetry</groupId>
                  <artifactId>opentelemetry-exporter-sender-jdk</artifactId>
                  <scope>runtime</scope>
              </dependency>
              <dependency>
                  <groupId>org.eclipse</groupId>
                  <artifactId>yasson</artifactId>
                  <version>3.0.4</version>
                  <scope>runtime</scope>
              </dependency>
          </dependencies>
          <build>
              <plugins>
                  <plugin>
                      <groupId>org.codehaus.mojo</groupId>
                      <artifactId>build-helper-maven-plugin</artifactId>
                      <version>3.3.0</version>
                      <executions>
                          <execution>
                              <id>add-dagger-client-sources</id>
                              <goals>
                                  <goal>add-source</goal>
                              </goals>
                              <configuration>
                                  <sources>
                                      <source>dagger/src/main/java</source>
                                  </sources>
                              </configuration>
                          </execution>
                      </executions>
                  </plugin>
              </plugins>
          </build>
      </profile>""";
}
