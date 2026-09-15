package io.github.lavyoung.lavshard.release;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Maven Central Portal 发布配置契约。
 */
class MavenCentralPublishingConfigurationTest {

    private static Document project;
    private static XPath xpath;

    @BeforeAll
    static void loadRootProject() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl",
                true
        );
        factory.setFeature(
                "http://xml.org/sax/features/external-general-entities",
                false
        );
        factory.setFeature(
                "http://xml.org/sax/features/external-parameter-entities",
                false
        );
        factory.setAttribute(
                XMLConstants.ACCESS_EXTERNAL_DTD,
                ""
        );
        factory.setAttribute(
                XMLConstants.ACCESS_EXTERNAL_SCHEMA,
                ""
        );

        project = factory.newDocumentBuilder().parse(rootPom().toFile());
        xpath = XPathFactory.newInstance().newXPath();
    }

    @Test
    void shouldUseCurrentCentralPortalPublishingPlugin() throws Exception {
        assertThat(text(
                "/project/properties/central-publishing-maven-plugin.version"
        )).isEqualTo("0.11.0");

        String plugin = "/project/build/pluginManagement/plugins/plugin"
                + "[artifactId='central-publishing-maven-plugin']";

        assertThat(text(plugin + "/groupId"))
                .isEqualTo("org.sonatype.central");
        assertThat(text(plugin + "/version"))
                .isEqualTo("${central-publishing-maven-plugin.version}");
        assertThat(text(plugin + "/extensions")).isEqualTo("true");
        assertThat(text(plugin + "/configuration/publishingServerId"))
                .isEqualTo("central");
        assertThat(text(plugin + "/configuration/autoPublish"))
                .isEqualTo("false");
        assertThat(text(plugin + "/configuration/waitUntil"))
                .isEqualTo("validated");
    }

    @Test
    void shouldAttachRequiredArtifactsInReleaseProfile() throws Exception {
        String releasePlugins = "/project/profiles/profile[id='release']"
                + "/build/plugins/plugin/artifactId";

        assertThat(strings(releasePlugins)).contains(
                "maven-source-plugin",
                "maven-javadoc-plugin",
                "maven-gpg-plugin",
                "central-publishing-maven-plugin"
        );
    }

    @Test
    void shouldNotRetainRetiredOssrhPublishingConfiguration()
            throws Exception {
        assertThat(count(
                "/project//plugin[artifactId='nexus-staging-maven-plugin']"
        )).isZero();
        assertThat(count(
                "/project/distributionManagement"
        )).isZero();
        assertThat(Files.readString(rootPom()))
                .doesNotContain(
                        "s01.oss.sonatype.org",
                        "nexus-staging-maven-plugin",
                        "<serverId>ossrh</serverId>"
                );
    }

    private static String text(String expression) throws Exception {
        return xpath.evaluate(expression, project).trim();
    }

    private static int count(String expression) throws Exception {
        return ((Number) xpath.evaluate(
                "count(" + expression + ")",
                project,
                XPathConstants.NUMBER
        )).intValue();
    }

    private static java.util.List<String> strings(String expression)
            throws Exception {
        var nodes = (org.w3c.dom.NodeList) xpath.evaluate(
                expression,
                project,
                XPathConstants.NODESET
        );
        var values = new java.util.ArrayList<String>(nodes.getLength());
        for (int index = 0; index < nodes.getLength(); index++) {
            values.add(nodes.item(index).getTextContent().trim());
        }
        return java.util.List.copyOf(values);
    }

    private static Path rootPom() throws Exception {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("pom.xml");
            if (Files.exists(directory.resolve(".git"))
                    && Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("Cannot locate lavshard root pom.xml");
    }
}
