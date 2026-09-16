package top.continew.sakura.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;
import com.jcraft.jsch.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import top.continew.sakura.agent.model.InfrastructureTaskRequest;
import top.continew.sakura.agent.support.AgentLogger;
import top.continew.sakura.agent.support.LocalActionPolicy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InfrastructureTaskExecutorHostKeyTest {

    private static final String SKIP_KEY = "sakura.agent.ssh-skip-host-key-check";
    private static final String HOSTS_KEY = "sakura.agent.known-hosts";

    @TempDir
    Path directory;

    private String previousSkip;
    private String previousHosts;
    private InfrastructureTaskExecutor executor;

    @BeforeEach
    void setUp() {
        previousSkip = System.getProperty(SKIP_KEY);
        previousHosts = System.getProperty(HOSTS_KEY);
        System.clearProperty(SKIP_KEY);
        System.clearProperty(HOSTS_KEY);
        executor = new InfrastructureTaskExecutor(new ObjectMapper(), directory.resolve("drivers"),
            new AgentLogger(directory.resolve("agent.log")),
            new LocalActionPolicy(directory.resolve("workspace"), List.of()));
    }

    @AfterEach
    void restoreProperties() {
        restoreProperty(SKIP_KEY, previousSkip);
        restoreProperty(HOSTS_KEY, previousHosts);
    }

    @ParameterizedTest
    @CsvSource({"SSH,false", "SFTP,false", "SSH,", "SFTP,"})
    void strictModeLoadsTrustedKeysAndRemainsTheDefault(String protocol, String skip) throws Exception {
        if (skip != null) {
            System.setProperty(SKIP_KEY, skip);
        }
        JSch jsch = new JSch();
        KeyPair hostKey = KeyPair.genKeyPair(jsch, KeyPair.RSA, 2048);
        try {
            ByteArrayOutputStream publicKey = new ByteArrayOutputStream();
            hostKey.writePublicKey(publicKey, "test-fixture");
            Path knownHosts = Files.writeString(directory.resolve("known_hosts"),
                "agent-test.invalid " + publicKey.toString(StandardCharsets.US_ASCII));
            System.setProperty(HOSTS_KEY, knownHosts.toString());
            Session session = jsch.getSession("tester", "agent-test.invalid", 22);
            executor.configureHostKeyChecking(jsch, session, request(protocol), protocol);
            assertEquals("yes", session.getConfig("StrictHostKeyChecking"));
            byte[] trustedKey = Base64.getDecoder().decode(
                session.getHostKeyRepository().getHostKey("agent-test.invalid", null)[0].getKey());
            assertEquals(HostKeyRepository.OK, session.getHostKeyRepository().check("agent-test.invalid", trustedKey));
            assertEquals(HostKeyRepository.NOT_INCLUDED,
                session.getHostKeyRepository().check("unknown-test.invalid", trustedKey));
            trustedKey[trustedKey.length - 1] ^= 1;
            assertEquals(HostKeyRepository.CHANGED,
                session.getHostKeyRepository().check("agent-test.invalid", trustedKey));
        } finally {
            hostKey.dispose();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SSH", "SFTP"})
    void strictModeRejectsMissingKnownHostsBeforeConnecting(String protocol) throws Exception {
        System.setProperty(SKIP_KEY, "false");
        System.setProperty(HOSTS_KEY, directory.resolve("missing-hosts").toString());
        JSch jsch = new JSch();
        Session session = jsch.getSession("tester", "agent-test.invalid", 22);
        assertThrows(IllegalStateException.class,
            () -> executor.configureHostKeyChecking(jsch, session, request(protocol), protocol));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SSH", "SFTP"})
    void explicitSkipDoesNotRequireKnownHosts(String protocol) throws Exception {
        System.setProperty(SKIP_KEY, "true");
        JSch jsch = new JSch();
        Session session = jsch.getSession("tester", "agent-test.invalid", 22);
        executor.configureHostKeyChecking(jsch, session, request(protocol), protocol);
        assertEquals("no", session.getConfig("StrictHostKeyChecking"));
    }

    private InfrastructureTaskRequest request(String protocol) {
        return new ObjectMapper().convertValue(Map.of("taskId", "host-key-check",
            "actionType", "SSH".equals(protocol) ? "server_command" : "server_file_upload"),
            InfrastructureTaskRequest.class);
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
