package top.continew.sakura.agent.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.continew.sakura.agent.support.LocalActionPolicy;

class AgentConfigurationTest {

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"agent-config.yml", "agent-config.development.yml", "agent-config.production.yml"})
    void loadsEveryShippedConfiguration(String name) throws Exception {
        AgentConfiguration config = AgentConfiguration.loadFromFile(Path.of("conf", name));
        Properties properties = config.resolveProperties(new Properties(), directory);
        boolean skipHostKeyCheck = !name.contains("production");
        assertEquals(skipHostKeyCheck, config.getSsh().isSkipHostKeyCheck());
        assertEquals(String.valueOf(skipHostKeyCheck), properties.getProperty("sakura.agent.ssh-skip-host-key-check"));
        assertEquals("", properties.getProperty(LocalActionPolicy.ALLOW_ROOTS_PROPERTY));
        assertEquals("false", properties.getProperty("sakura.agent.captcha-ocr-enabled"));
        assertEquals("false", properties.getProperty("sakura.agent.desktop-enabled"));
        assertEquals("", properties.getProperty(LocalActionPolicy.RUNTIME_PROPERTY_ALLOWLIST));
    }

    @Test
    void mapsAllYamlFieldsAndResolvesPathsAgainstAgentWorkingDirectory() throws Exception {
        Properties properties = load("""
            ssh:
              skip-host-key-check: true
              known-hosts: conf/custom_hosts
            jdbc:
              driver-dir: custom-drivers
            local-action:
              workspace: sandbox
              allow-roots: [uploads]
            logging:
              file: logs/custom.log
            task:
              ledger-file: data/custom.json
            server:
              bind: 127.0.0.2
              port: 19222
            features:
              desktop:
                enabled: true
              captcha-ocr:
                enabled: true
                python: tools/python
                script: sandbox/ocr.py
              runtime-properties:
                Dev: [APP_VERSION, BUILD_NUMBER]
            """).resolveProperties(new Properties(), directory);
        assertEquals("127.0.0.2", properties.getProperty("sakura.agent.bind"));
        assertEquals("19222", properties.getProperty("sakura.agent.port"));
        assertPath(properties, "known-hosts", "conf/custom_hosts");
        assertPath(properties, "driver-dir", "custom-drivers");
        assertPath(properties, "workspace", "sandbox");
        assertPath(properties, "file-allow-roots", "uploads");
        assertPath(properties, "log-file", "logs/custom.log");
        assertPath(properties, "ledger-file", "data/custom.json");
        assertPath(properties, "captcha-ocr-python", "tools/python");
        assertPath(properties, "captcha-ocr-script", "sandbox/ocr.py");
        assertEquals("true", properties.getProperty("sakura.agent.desktop-enabled"));
        assertEquals("true", properties.getProperty("sakura.agent.captcha-ocr-enabled"));
        assertEquals("dev:APP_VERSION;dev:BUILD_NUMBER", properties.getProperty(LocalActionPolicy.RUNTIME_PROPERTY_ALLOWLIST));
        assertFalse(Files.exists(directory.resolve("sandbox")));
        assertThrows(IllegalStateException.class, () -> LocalActionPolicy.validateConfiguration(properties));
        Files.createDirectory(directory.resolve("uploads"));
        assertDoesNotThrow(() -> LocalActionPolicy.validateConfiguration(properties));
        assertFalse(Files.exists(directory.resolve("sandbox")));
    }

    @Test
    void explicitJvmPropertiesWinWithoutMutatingInput() throws Exception {
        Properties overrides = new Properties();
        overrides.setProperty("sakura.agent.ssh-skip-host-key-check", "true");
        overrides.setProperty("sakura.agent.port", "19444");
        overrides.setProperty("sakura.agent.workspace", directory.resolve("override").toString());
        overrides.setProperty("sakura.agent.file-allow-roots", "");
        overrides.setProperty("sakura.agent.runtime-property-allowlist", "app:VERSION");
        overrides.setProperty("sakura.agent.desktop-enabled", "true");
        overrides.setProperty("sakura.agent.captcha-ocr-enabled", "true");
        Properties before = (Properties)overrides.clone();
        Properties resolved = load("ssh:\n  skip-host-key-check: false\n").resolveProperties(overrides, directory);
        for (String key : overrides.stringPropertyNames()) {
            assertEquals(overrides.getProperty(key), resolved.getProperty(key), key);
        }
        assertEquals(before, overrides);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "unknown-option: true\n",
        "ssh:\n  skip-host-key-chek: true\n",
        "ssh:\n  skip-host-key-check: false\n  skip-host-key-check: true\n",
        "ssh: null\n",
        "ssh:\n  skip-host-key-check: null\n",
        "ssh:\n  skip-host-key-check: 'false'\n",
        "ssh:\n  skip-host-key-check: 1\n",
        "local-action: null\n",
        "local-action:\n  allow-roots: [null]\n",
        "features:\n  runtime-properties: null\n",
        "features:\n  runtime-properties:\n    dev: null\n",
        "ssh: {}\n---\nssh:\n  skip-host-key-check: true\n"
    })
    void rejectsMalformedOrAmbiguousYaml(String yaml) {
        assertThrows(Exception.class, () -> load(yaml).resolveProperties(new Properties(), directory));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "65536", "-1", "not-a-port"})
    void rejectsInvalidEffectivePort(String port) {
        Properties overrides = new Properties();
        overrides.setProperty("sakura.agent.port", port);
        assertThrows(IllegalArgumentException.class,
            () -> new AgentConfiguration().resolveProperties(overrides, directory));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sakura.agent.ssh-skip-host-key-check", "sakura.agent.desktop-enabled",
        "sakura.agent.captcha-ocr-enabled"})
    void rejectsInvalidJvmBooleanInsteadOfSilentlyDisablingIt(String key) {
        Properties overrides = new Properties();
        overrides.setProperty(key, "not-a-boolean");
        assertThrows(IllegalArgumentException.class,
            () -> new AgentConfiguration().resolveProperties(overrides, directory));
    }

    @Test
    void explicitFalseJvmOverrideAlsoWinsOverDevelopmentYaml() throws Exception {
        Properties overrides = new Properties();
        overrides.setProperty("sakura.agent.ssh-skip-host-key-check", "false");
        Properties properties = load("ssh:\n  skip-host-key-check: true\n")
            .resolveProperties(overrides, directory);
        assertEquals("false", properties.getProperty("sakura.agent.ssh-skip-host-key-check"));
    }

    @Test
    void ocrRemainsDisabledUnlessEnabledOrUsingLegacyJvmConfiguration() throws Exception {
        Properties legacy = new Properties();
        legacy.setProperty("sakura.agent.captcha-ocr-python", "python");
        legacy.setProperty("sakura.agent.captcha-ocr-script", "workspace/ocr.py");
        assertEquals("false", new AgentConfiguration().resolveProperties(new Properties(), directory)
            .getProperty("sakura.agent.captcha-ocr-enabled"));
        assertEquals("true", new AgentConfiguration().resolveProperties(legacy, directory)
            .getProperty("sakura.agent.captcha-ocr-enabled"));
        AgentConfiguration disabled = load("features:\n  captcha-ocr:\n    enabled: false\n");
        assertEquals("false", disabled.resolveProperties(legacy, directory)
            .getProperty("sakura.agent.captcha-ocr-enabled"));
        legacy.setProperty("sakura.agent.captcha-ocr-enabled", "true");
        assertEquals("true", disabled.resolveProperties(legacy, directory)
            .getProperty("sakura.agent.captcha-ocr-enabled"));
    }

    @Test
    void preflightRejectsUnsafeWorkspaceWithoutCreatingDirectories() throws Exception {
        Properties properties = new AgentConfiguration().resolveProperties(new Properties(), directory);
        assertDoesNotThrow(() -> LocalActionPolicy.validateConfiguration(properties));
        assertFalse(Files.exists(directory.resolve("workspace")));
        properties.setProperty(LocalActionPolicy.WORKSPACE_PROPERTY, directory.getRoot().toString());
        assertThrows(IllegalArgumentException.class, () -> LocalActionPolicy.validateConfiguration(properties));
        Path file = Files.writeString(directory.resolve("not-a-directory"), "test");
        properties.setProperty(LocalActionPolicy.WORKSPACE_PROPERTY, file.toString());
        assertThrows(IllegalArgumentException.class, () -> LocalActionPolicy.validateConfiguration(properties));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside", "bad;root", "bad,root"})
    void rejectsAmbiguousAllowRootPaths(String path) throws Exception {
        AgentConfiguration config = load("local-action:\n  allow-roots:\n    - '" + path + "'\n");
        assertThrows(IllegalArgumentException.class, () -> config.resolveProperties(new Properties(), directory));
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "APP;OTHER", "APP:OTHER", "${APP}"})
    void rejectsRuntimeAllowlistDelimiterInjection(String key) throws Exception {
        AgentConfiguration config = load("features:\n  runtime-properties:\n    dev: ['" + key + "']\n");
        assertThrows(IllegalArgumentException.class, () -> config.resolveProperties(new Properties(), directory));
    }

    private AgentConfiguration load(String yaml) throws IOException {
        return AgentConfiguration.loadFromFile(Files.writeString(directory.resolve("config.yml"), yaml));
    }

    private void assertPath(Properties properties, String suffix, String relative) {
        assertEquals(directory.resolve(relative).normalize().toString(), properties.getProperty("sakura.agent." + suffix));
    }
}
