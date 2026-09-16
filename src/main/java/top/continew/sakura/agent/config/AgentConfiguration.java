package top.continew.sakura.agent.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import top.continew.sakura.agent.support.LocalActionPolicy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Agent 配置文件模型。
 * 从 YAML 配置文件加载，优先级低于 JVM 系统属性（-D 参数）。
 */
public class AgentConfiguration {

    private SshConfig ssh = new SshConfig();
    private JdbcConfig jdbc = new JdbcConfig();
    @JsonProperty("local-action")
    private LocalActionConfig localAction = new LocalActionConfig();
    private LoggingConfig logging = new LoggingConfig();
    private TaskConfig task = new TaskConfig();
    private ServerConfig server = new ServerConfig();
    private FeaturesConfig features = new FeaturesConfig();

    /** 安装器调用独立入口，旧 JAR 缺少入口时直接失败，不能误启动旧版 HTTP 服务。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("配置预检需要一个 Agent 工作目录参数");
        }
        Path workingDirectory = Path.of(args[0]).toAbsolutePath().normalize();
        Path configFile = workingDirectory.resolve(requireText(System.getProperty("sakura.agent.config"),
            "sakura.agent.config")).normalize();
        Properties resolved = loadFromFile(configFile).resolveProperties(System.getProperties(), workingDirectory);
        LocalActionPolicy.validateConfiguration(resolved);
        System.out.println("Agent 配置检查通过 configFile=" + configFile + " sshSkipHostKeyCheck="
            + resolved.getProperty("sakura.agent.ssh-skip-host-key-check") + " knownHosts="
            + resolved.getProperty("sakura.agent.known-hosts"));
    }

    public static AgentConfiguration loadFromFile(Path configFile) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        // 拼写错误、重复键和显式空值必须报错，不能静默改变安全开关。
        mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        mapper.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        mapper.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        mapper.setDefaultSetterInfo(JsonSetter.Value.forValueNulls(Nulls.FAIL));
        AgentConfiguration config = mapper.readValue(configFile.toFile(), AgentConfiguration.class);
        if (config == null) {
            throw new IOException("Agent 配置文件不能为空：" + configFile);
        }
        return config;
    }

    /** 合并到独立副本，预检与启动共享规则，且不修改调用方的 JVM 属性。 */
    public Properties resolveProperties(Properties overrides, Path workingDirectory) {
        Properties result = new Properties();
        result.putAll(overrides);
        result.putIfAbsent("sakura.agent.bind", server.bind);
        result.putIfAbsent("sakura.agent.port", String.valueOf(server.port));
        result.putIfAbsent("sakura.agent.ssh-skip-host-key-check", String.valueOf(ssh.skipHostKeyCheck));
        result.putIfAbsent("sakura.agent.desktop-enabled", String.valueOf(features.desktop.enabled));

        // 旧部署只配置 Python 和脚本即可启用 OCR；显式 enabled=false 必须始终关闭能力。
        boolean legacyOcr = overrides.containsKey("sakura.agent.captcha-ocr-python")
            && overrides.containsKey("sakura.agent.captcha-ocr-script");
        result.putIfAbsent("sakura.agent.captcha-ocr-enabled",
            String.valueOf(features.captchaOcr.enabled == null ? legacyOcr : features.captchaOcr.enabled));
        result.putIfAbsent("sakura.agent.captcha-ocr-python", features.captchaOcr.python);
        resolvePath(result, "sakura.agent.known-hosts", ssh.knownHosts, workingDirectory);
        resolvePath(result, "sakura.agent.driver-dir", jdbc.driverDir, workingDirectory);
        resolvePath(result, "sakura.agent.log-file", logging.file, workingDirectory);
        resolvePath(result, "sakura.agent.ledger-file", task.ledgerFile, workingDirectory);
        resolvePath(result, "sakura.agent.workspace", localAction.workspace, workingDirectory);
        resolvePath(result, "sakura.agent.captcha-ocr-script", features.captchaOcr.script, workingDirectory);
        String python = result.getProperty("sakura.agent.captcha-ocr-python");
        // 裸命令名由 PATH 查找；带目录的解释器路径与其他路径保持相同的工作目录语义。
        if (python.contains("/") || python.contains("\\")) {
            resolvePath(result, "sakura.agent.captcha-ocr-python", python, workingDirectory);
        }
        if (!result.containsKey("sakura.agent.file-allow-roots")) {
            List<String> roots = new ArrayList<>();
            for (String root : localAction.allowRoots) {
                Path path = Path.of(requireText(root, "local-action.allow-roots"));
                for (Path segment : path) {
                    if ("..".equals(segment.toString())) {
                        throw new IllegalArgumentException("local-action.allow-roots 不能包含上级目录片段");
                    }
                }
                String absolute = workingDirectory.resolve(path).toAbsolutePath().normalize().toString();
                if (absolute.contains(",") || absolute.contains(";")) {
                    throw new IllegalArgumentException("local-action.allow-roots 路径不能包含逗号或分号");
                }
                roots.add(absolute);
            }
            result.setProperty("sakura.agent.file-allow-roots", String.join(";", roots));
        }
        if (!result.containsKey("sakura.agent.runtime-property-allowlist")) {
            result.setProperty("sakura.agent.runtime-property-allowlist", runtimePropertyAllowlist());
        }
        requireText(result.getProperty("sakura.agent.bind"), "sakura.agent.bind");
        int port = Integer.parseInt(result.getProperty("sakura.agent.port"));
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("sakura.agent.port 必须在 1-65535 之间");
        }
        for (String key : List.of("sakura.agent.ssh-skip-host-key-check", "sakura.agent.desktop-enabled",
            "sakura.agent.captcha-ocr-enabled")) {
            String value = result.getProperty(key);
            if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                throw new IllegalArgumentException(key + " 只能是 true 或 false");
            }
        }
        return result;
    }

    private static void resolvePath(Properties properties, String key, String fallback, Path workingDirectory) {
        String value = requireText(properties.getProperty(key, fallback), key);
        properties.setProperty(key, workingDirectory.resolve(Path.of(value)).toAbsolutePath().normalize().toString());
    }

    private String runtimePropertyAllowlist() {
        List<String> entries = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : features.runtimeProperties.entrySet()) {
            String profile = allowlistPart(entry.getKey()).toLowerCase(Locale.ROOT);
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("features.runtime-properties 必须配置属性键列表");
            }
            for (String key : entry.getValue()) {
                entries.add(profile + ":" + allowlistPart(key));
            }
        }
        return String.join(";", entries);
    }

    private static String allowlistPart(String value) {
        String part = requireText(value, "features.runtime-properties");
        // 禁止分隔符注入把 profile:key 意外扩展成无 profile 限制的授权。
        if (part.matches(".*[,:;*\\s].*") || part.contains("${")) {
            throw new IllegalArgumentException("features.runtime-properties 只允许无通配符、无分隔符的精确键");
        }
        return part;
    }

    private static String requireText(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " 不能为空");
        }
        return value.trim();
    }

    public SshConfig getSsh() {
        return ssh;
    }

    public void setSsh(SshConfig ssh) {
        this.ssh = ssh;
    }

    public JdbcConfig getJdbc() {
        return jdbc;
    }

    public void setJdbc(JdbcConfig jdbc) {
        this.jdbc = jdbc;
    }

    public LocalActionConfig getLocalAction() {
        return localAction;
    }

    public void setLocalAction(LocalActionConfig localAction) {
        this.localAction = localAction;
    }

    public LoggingConfig getLogging() {
        return logging;
    }

    public void setLogging(LoggingConfig logging) {
        this.logging = logging;
    }

    public TaskConfig getTask() {
        return task;
    }

    public void setTask(TaskConfig task) {
        this.task = task;
    }

    public ServerConfig getServer() {
        return server;
    }

    public void setServer(ServerConfig server) {
        this.server = server;
    }

    public FeaturesConfig getFeatures() {
        return features;
    }

    public void setFeatures(FeaturesConfig features) {
        this.features = features;
    }

    public static class SshConfig {
        @JsonProperty("skip-host-key-check")
        private boolean skipHostKeyCheck = false;

        @JsonProperty("known-hosts")
        private String knownHosts = "conf/known_hosts";

        public boolean isSkipHostKeyCheck() {
            return skipHostKeyCheck;
        }

        public void setSkipHostKeyCheck(boolean skipHostKeyCheck) {
            this.skipHostKeyCheck = skipHostKeyCheck;
        }

        public String getKnownHosts() {
            return knownHosts;
        }

        public void setKnownHosts(String knownHosts) {
            this.knownHosts = knownHosts;
        }
    }

    public static class JdbcConfig {
        @JsonProperty("driver-dir")
        private String driverDir = "drivers";

        public String getDriverDir() {
            return driverDir;
        }

        public void setDriverDir(String driverDir) {
            this.driverDir = driverDir;
        }
    }

    public static class LocalActionConfig {
        private String workspace = "workspace";

        @JsonProperty("allow-roots")
        // workspace 始终授权；默认不额外暴露目录，也不要求不存在的 temp 目录。
        private List<String> allowRoots = List.of();

        public String getWorkspace() {
            return workspace;
        }

        public void setWorkspace(String workspace) {
            this.workspace = workspace;
        }

        public List<String> getAllowRoots() {
            return allowRoots;
        }

        public void setAllowRoots(List<String> allowRoots) {
            this.allowRoots = allowRoots;
        }
    }

    public static class LoggingConfig {
        private String file = "logs/agent.log";

        public String getFile() {
            return file;
        }

        public void setFile(String file) {
            this.file = file;
        }
    }

    public static class TaskConfig {
        @JsonProperty("ledger-file")
        private String ledgerFile = "data/task-ledger.json";

        public String getLedgerFile() {
            return ledgerFile;
        }

        public void setLedgerFile(String ledgerFile) {
            this.ledgerFile = ledgerFile;
        }
    }

    public static class ServerConfig {
        private String bind = "127.0.0.1";
        private int port = 19091;

        public String getBind() {
            return bind;
        }

        public void setBind(String bind) {
            this.bind = bind;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }
    }

    public static class FeaturesConfig {
        @JsonProperty("captcha-ocr")
        private CaptchaOcrConfig captchaOcr = new CaptchaOcrConfig();

        private DesktopConfig desktop = new DesktopConfig();

        @JsonProperty("runtime-properties")
        private Map<String, List<String>> runtimeProperties = Map.of();

        public CaptchaOcrConfig getCaptchaOcr() {
            return captchaOcr;
        }

        public void setCaptchaOcr(CaptchaOcrConfig captchaOcr) {
            this.captchaOcr = captchaOcr;
        }

        public DesktopConfig getDesktop() {
            return desktop;
        }

        public void setDesktop(DesktopConfig desktop) {
            this.desktop = desktop;
        }

        public Map<String, List<String>> getRuntimeProperties() {
            return runtimeProperties;
        }

        public void setRuntimeProperties(Map<String, List<String>> runtimeProperties) {
            this.runtimeProperties = runtimeProperties;
        }
    }

    public static class CaptchaOcrConfig {
        private Boolean enabled;
        private String python = "python3";
        private String script = "scripts/ocr.py";

        public boolean isEnabled() {
            return Boolean.TRUE.equals(enabled);
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public String getPython() {
            return python;
        }

        public void setPython(String python) {
            this.python = python;
        }

        public String getScript() {
            return script;
        }

        public void setScript(String script) {
            this.script = script;
        }
    }

    public static class DesktopConfig {
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
