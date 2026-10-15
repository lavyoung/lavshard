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

    /**
     * 安全解析仓库根 POM，供发布配置契约共享。
     *
     * @throws Exception XML 解析器初始化或 POM 读取失败时抛出
     */
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

    /**
     * 验证 Central Publisher 扩展仅由正式发布 profile 激活。
     *
     * @throws Exception XPath 查询失败时抛出
     */
    @Test
    void shouldScopeCentralPortalPluginToCentralReleaseProfile()
            throws Exception {
        assertThat(text(
                "/project/properties/central-publishing-maven-plugin.version"
        )).isEqualTo("0.8.0");

        String plugin = "/project/profiles/profile[id='central-release']"
                + "/build/plugins/plugin"
                + "[artifactId='central-publishing-maven-plugin']";

        assertThat(text(plugin + "/groupId"))
                .isEqualTo("org.sonatype.central");
        assertThat(text(plugin + "/version"))
                .isEqualTo("${central-publishing-maven-plugin.version}");
        assertThat(text(plugin + "/extensions")).isEqualTo("true");
        assertThat(text(plugin + "/configuration/publishingServerId"))
                .isEqualTo("central");
        assertThat(strings(
                plugin + "/configuration/excludeArtifacts/excludeArtifact"
        )).containsExactly(
                "lavshard-example",
                "lavshard-example-simple",
                "lavshard-example-multi-datasource",
                "lavshard-test"
        );
        assertThat(count(
                "/project/build/pluginManagement/plugins/plugin"
                        + "[artifactId='central-publishing-maven-plugin']"
        )).isZero();
        assertThat(count(
                "/project/build/plugins/plugin"
                        + "[artifactId='central-publishing-maven-plugin']"
        )).isZero();
    }

    /**
     * 验证源码与 Javadoc 制品由常规构建生成，签名仅由正式发布启用。
     *
     * @throws Exception XPath 查询失败时抛出
     */
    @Test
    void shouldAttachRequiredArtifactsForEveryBuild() throws Exception {
        String buildPlugins = "/project/build/plugins/plugin/artifactId";
        String releasePlugins = "/project/profiles/profile[id='central-release']"
                + "/build/plugins/plugin/artifactId";

        assertThat(strings(buildPlugins)).contains(
                "maven-source-plugin",
                "maven-javadoc-plugin"
        );
        assertThat(strings(releasePlugins)).containsExactlyInAnyOrder(
                "maven-gpg-plugin",
                "central-publishing-maven-plugin"
        );
    }

    /**
     * 验证快照发布使用 Central Portal 的 Maven Snapshot 仓库。
     *
     * @throws Exception XPath 查询失败时抛出
     */
    @Test
    void shouldConfigureCentralSnapshotRepository() throws Exception {
        String repository = "/project/profiles/profile[id='central-snapshot']"
                + "/distributionManagement/snapshotRepository";

        assertThat(text(repository + "/id")).isEqualTo("central");
        assertThat(text(repository + "/url")).isEqualTo(
                "https://central.sonatype.com/repository/maven-snapshots/"
        );
    }

    /**
     * 验证正式发布使用项目约定的 GPG 程序和签名密钥。
     *
     * @throws Exception XPath 查询失败时抛出
     */
    @Test
    void shouldConfigureReleaseSigningKey() throws Exception {
        String plugin = "/project/profiles/profile[id='central-release']"
                + "/build/plugins/plugin[artifactId='maven-gpg-plugin']";

        assertThat(text(plugin + "/version"))
                .isEqualTo("${maven-gpg-plugin.version}");
        assertThat(text(plugin + "/configuration/executable"))
                .isEqualTo("${gpg.executable}");
        assertThat(text(plugin + "/configuration/keyname"))
                .isEqualTo("${gpg.keyname}");
        assertThat(text(plugin + "/executions/execution/phase"))
                .isEqualTo("verify");
        assertThat(strings(plugin + "/executions/execution/goals/goal"))
                .containsExactly("sign");
    }

    /**
     * 验证旧 OSSRH 发布端点和 Staging 插件不会重新进入构建配置。
     *
     * @throws Exception XPath 查询或 POM 读取失败时抛出
     */
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
                        "<id>release</id>",
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

    /**
     * 获取 XPath 命中的全部文本值，并转换为不可变列表。
     *
     * @param expression XPath 表达式
     * @return 按文档顺序排列的文本值
     * @throws Exception XPath 查询失败时抛出
     */
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

    /**
     * 从当前 Maven 模块向上定位 Git 仓库根 POM。
     *
     * @return 仓库根 POM 路径
     * @throws Exception 找不到仓库根 POM 时抛出
     */
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
