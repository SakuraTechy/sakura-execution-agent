package top.continew.sakura.agent.model;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Agent 的短时执行请求；只允许在本机受控链路上传输。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InfrastructureTaskRequest(@JsonAlias({"task_id"}) String taskId,
                                        @JsonAlias({"action_type"}) String actionType,
                                        @JsonAlias({"timeout_ms"}) long timeoutMs,
                                        String command,
                                        String shell,
                                        @JsonAlias({"ssh_target"}) SshTarget sshTarget,
                                        @JsonAlias({"jdbc_target"}) JdbcTarget jdbcTarget,
                                        @JsonAlias({"sql_mode"}) String sqlMode,
                                        String sql,
                                        List<JdbcParameter> parameters,
                                        @JsonAlias({"max_rows"}) int maxRows,
                                        @JsonAlias({"mongo_target"}) MongoTarget mongoTarget,
                                        @JsonAlias({"mongo_operation"}) String mongoOperation,
                                        String collection,
                                        Map<String, Object> filter,
                                        Map<String, Object> document,
                                        @JsonAlias({"path", "fileRef", "file_ref", "file_path"}) String filePath,
                                        @JsonAlias({"path_mode"}) String pathMode,
                                        @JsonAlias({"file_pattern"}) String filePattern,
                                        @JsonAlias({"max_results"}) Integer maxResults,
                                        Boolean recursive,
                                        @JsonAlias({"source_path"}) String sourcePath,
                                        @JsonAlias({"remote_path"}) String remotePath,
                                        Boolean overwrite,
                                        @JsonAlias({"variable_name"}) String variableName,
                                        @JsonAlias({"property_key"}) String propertyKey,
                                        String profile,
                                        @JsonAlias({"captcha_image_base64", "image_base64"}) String captchaImageBase64,
                                        @JsonAlias({"systemInfoType", "system_info_type", "info_type"}) String infoType,
                                        @JsonAlias({"ip_prefix"}) String ipPrefix,
                                        Integer start,
                                        Integer end,
                                        Integer x,
                                        Integer y,
                                        @JsonAlias({"host_shell"}) String hostShell,
                                        @JsonAlias({"working_directory"}) String workingDirectory,
                                        String capability,
                                        List<String> capabilities,
                                        @JsonAlias({"approval_granted", "approved"}) Boolean approvalGranted,
                                        @JsonAlias({"approval_id"}) String approvalId,
                                        @JsonAlias({"risk_level"}) String riskLevel,
                                        @JsonAlias({"read_only_enforced"}) Boolean readOnlyEnforced,
                                        @JsonAlias({"command_template_id"}) String commandTemplateId,
                                        @JsonAlias({"approval_digest"}) String approvalDigest) {

    public record SshTarget(String host,
                            Integer port,
                            String username,
                            String password,
                            @JsonAlias({"known_host_fingerprint"}) String knownHostFingerprint,
                            String platform) {
    }

    public record JdbcTarget(@JsonAlias({"driver_profile"}) String driverProfile,
                             @JsonAlias({"driver_class"}) String driverClass,
                             @JsonAlias({"jdbc_url"}) String jdbcUrl,
                             String username,
                             String password) {
    }

    public record JdbcParameter(String name,
                                Integer position,
                                String jdbcType,
                                String typeName,
                                String direction,
                                Object value) {

        public JdbcParameter(String jdbcType, Object value) {
            this(null, null, jdbcType, null, "IN", value);
        }
    }

    public record MongoTarget(String connectionString, String database) {
    }
}
