package com.yak.zerotrust.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies least-privilege PostgreSQL roles and the existing-volume ownership upgrade. */
@EnabledIfEnvironmentVariable(named = "PHASE14_INTEGRATION", matches = "true")
class Phase14DatabaseRolesComposeIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final String ADMIN_PSQL_COMMAND =
            "printf '%s\\n' \"$1\" | psql -v ON_ERROR_STOP=1 "
                    + "-v runtime_username=\"$DB_USERNAME\" -v migration_username=\"$DB_MIGRATION_USERNAME\" "
                    + "-U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\"";
    private static final String ADMIN_PSQL_QUERY_COMMAND =
            "printf '%s\\n' \"$1\" | psql -v ON_ERROR_STOP=1 -t -A "
                    + "-v runtime_username=\"$DB_USERNAME\" -v migration_username=\"$DB_MIGRATION_USERNAME\" "
                    + "-U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\"";
    private static final String RUNTIME_PSQL_COMMAND =
            "printf '%s\\n' \"$1\" | PGPASSWORD=\"$DB_PASSWORD\" psql -v ON_ERROR_STOP=1 -h 127.0.0.1 "
                    + "-U \"$DB_USERNAME\" -d \"$POSTGRES_DB\"";
    private static final String MIGRATION_PSQL_COMMAND =
            "printf '%s\\n' \"$1\" | PGPASSWORD=\"$DB_MIGRATION_PASSWORD\" "
                    + "psql -v ON_ERROR_STOP=1 -h 127.0.0.1 -U \"$DB_MIGRATION_USERNAME\" -d \"$POSTGRES_DB\"";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String baseUrl = environment("PHASE14_BASE_URL", "http://127.0.0.1:8080");

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void migrationRoleOwnsSchemaWhileRuntimeCanOnlyPerformApplicationDml() throws Exception {
        String adminUsername = environment("PHASE14_ADMIN_USERNAME", "admin")
                .trim()
                .toLowerCase(Locale.ROOT);
        String adminPassword = requiredEnvironment("PHASE14_ADMIN_PASSWORD", "ADMIN_PASSWORD");
        String adminToken = login(adminUsername, adminPassword);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase(Locale.ROOT);
        String legacyTable = "phase14_legacy_" + suffix;
        boolean legacyTableCreated = false;
        String testPolicyName = "Phase 14 runtime DML " + suffix;

        try {
            assertRuntimeRoleIsUnprivileged();
            assertMigrationRoleOwnsApplicationObjects();
            assertAppendOnlyTriggersAreOwnedAndEnabled();
            assertRuntimeCannotCreateOrAlterObjects(suffix);
            assertMigrationRoleCanRunDdl(suffix);

            legacyTableCreated = true;
            createLegacyRuntimeOwnedTable(legacyTable);
            assertThat(query("SELECT pg_get_userbyid(c.relowner) = :'runtime_username' "
                    + "FROM pg_class c WHERE c.oid = 'public." + legacyTable + "'::regclass"))
                    .as("fixture should represent a pre-hardening runtime-owned table")
                    .isEqualTo("t");

            rerunRoleBootstrap();
            assertRuntimeRoleIsUnprivileged();
            assertMigrationRoleOwnsApplicationObjects();
            assertAppendOnlyTriggersAreOwnedAndEnabled();
            assertRuntimeCannotCreateOrAlterObjects(suffix);
            assertThat(query("SELECT pg_get_userbyid(c.relowner) = :'migration_username' "
                    + "FROM pg_class c WHERE c.oid = 'public." + legacyTable + "'::regclass"))
                    .as("bootstrap should transfer an existing table to the migration role")
                    .isEqualTo("t");
            assertThat(query("SELECT pg_get_userbyid(c.relowner) = :'migration_username' "
                    + "FROM pg_class c WHERE c.oid = pg_get_serial_sequence('public." + legacyTable
                    + "', 'id')::regclass"))
                    .as("bootstrap should transfer an existing table's sequence")
                    .isEqualTo("t");
            assertRuntimeCanUseMigratedLegacyTable(legacyTable);

            exerciseBackendRuntimeDml(adminToken, testPolicyName, suffix);
            assertRuntimeCannotMutateAppendOnlyAudit(testPolicyName);
        } finally {
            try {
                executeAdminSql("DELETE FROM public.policies WHERE name = '" + testPolicyName + "'");
            } finally {
                if (legacyTableCreated) {
                    executeAdminSql("DROP TABLE IF EXISTS public." + legacyTable);
                }
            }
        }
    }

    private void assertRuntimeRoleIsUnprivileged() throws Exception {
        assertThat(query("SELECT NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole "
                + "AND NOT rolreplication AND NOT rolbypassrls "
                + "FROM pg_roles WHERE rolname = :'runtime_username'"))
                .as("runtime role must not have elevated cluster privileges")
                .isEqualTo("t");
        assertThat(query("SELECT NOT EXISTS (SELECT 1 FROM pg_auth_members "
                + "WHERE member = (SELECT oid FROM pg_roles WHERE rolname = :'runtime_username'))"))
                .as("runtime role must not inherit another role's privileges")
                .isEqualTo("t");
        assertThat(query("SELECT datdba <> (SELECT oid FROM pg_roles WHERE rolname = :'runtime_username') "
                + "FROM pg_database WHERE datname = current_database()"))
                .as("runtime role must not own the database")
                .isEqualTo("t");
        assertThat(query("SELECT has_schema_privilege(:'runtime_username', 'public', 'USAGE') "
                + "AND NOT has_schema_privilege(:'runtime_username', 'public', 'CREATE') "
                + "AND NOT has_database_privilege(:'runtime_username', current_database(), 'CREATE') "
                + "AND NOT has_database_privilege(:'runtime_username', current_database(), 'TEMPORARY')"))
                .as("runtime should only have schema usage, not object-creation privileges")
                .isEqualTo("t");
        assertThat(query("SELECT count(*) = 0 FROM pg_class c "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p', 'S', 'v', 'm', 'f') "
                + "AND c.relowner = (SELECT oid FROM pg_roles WHERE rolname = :'runtime_username')"))
                .as("runtime must not own public tables, sequences, or views")
                .isEqualTo("t");
    }

    private void assertMigrationRoleOwnsApplicationObjects() throws Exception {
        assertThat(query("SELECT NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole "
                + "AND NOT rolreplication AND NOT rolbypassrls "
                + "FROM pg_roles WHERE rolname = :'migration_username'"))
                .as("migration role must be DDL-capable only within the application schema")
                .isEqualTo("t");
        assertThat(query("SELECT NOT EXISTS (SELECT 1 FROM pg_auth_members "
                + "WHERE member = (SELECT oid FROM pg_roles WHERE rolname = :'migration_username'))"))
                .as("migration role must not inherit another role's privileges")
                .isEqualTo("t");
        assertThat(query("SELECT datdba <> (SELECT oid FROM pg_roles WHERE rolname = :'migration_username') "
                + "FROM pg_database WHERE datname = current_database()"))
                .as("migration role must not own the database")
                .isEqualTo("t");
        assertThat(query("SELECT nspowner = (SELECT oid FROM pg_roles WHERE rolname = :'migration_username') "
                + "FROM pg_namespace WHERE nspname = 'public'"))
                .as("migration role should own the application schema")
                .isEqualTo("t");
        assertThat(query("SELECT count(*) > 0 AND bool_and("
                + "c.relowner = (SELECT oid FROM pg_roles WHERE rolname = :'migration_username')) "
                + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p', 'S', 'v', 'm', 'f')"))
                .as("Flyway-created application tables and sequences should be migration-owned")
                .isEqualTo("t");
        assertThat(query("SELECT pg_get_userbyid(p.proowner) = :'migration_username' "
                + "FROM pg_proc p WHERE p.oid = to_regprocedure('public.reject_audit_history_mutation()')"))
                .as("append-only trigger function should be migration-owned")
                .isEqualTo("t");
    }

    private void assertAppendOnlyTriggersAreOwnedAndEnabled() throws Exception {
        assertThat(query("SELECT count(*) = 4 FROM pg_trigger t "
                + "JOIN pg_class c ON c.oid = t.tgrelid "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = 'public' AND t.tgname IN ("
                + "'access_audits_append_only', 'device_ownership_audits_append_only', "
                + "'device_status_audits_append_only', 'policy_change_audits_append_only') "
                + "AND t.tgenabled = 'O'"))
                .as("all four append-only triggers should remain installed and enabled")
                .isEqualTo("t");
    }

    private void assertRuntimeCannotCreateOrAlterObjects(String suffix) throws Exception {
        assertSqlRejected(runRuntimePsql("CREATE TABLE public.phase14_forbidden_" + suffix + " (id integer)"),
                "creating a public table", "permission denied");
        assertSqlRejected(runRuntimePsql("ALTER TABLE public.access_audits ADD COLUMN phase14_forbidden_" + suffix
                        + " integer"),
                "altering an application table", "must be owner");
        assertSqlRejected(runRuntimePsql("ALTER TABLE public.access_audits DISABLE TRIGGER access_audits_append_only"),
                "disabling an append-only trigger", "must be owner");
        assertSqlRejected(runRuntimePsql("DROP TRIGGER access_audits_append_only ON public.access_audits"),
                "dropping an append-only trigger", "must be owner");
        assertSqlRejected(runRuntimePsql("TRUNCATE TABLE public.access_audits"),
                "truncating append-only audit history", "permission denied");
        assertSqlRejected(runRuntimePsql("CREATE OR REPLACE FUNCTION public.reject_audit_history_mutation() "
                        + "RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END; $$"),
                "replacing the append-only trigger function", "permission denied");
    }

    private void assertMigrationRoleCanRunDdl(String suffix) throws Exception {
        String table = "phase14_migration_ddl_" + suffix;
        CommandResult result = runMigrationPsql("BEGIN; CREATE TABLE public." + table
                + " (id integer PRIMARY KEY); ALTER TABLE public." + table
                + " ADD COLUMN note text; DROP TABLE public." + table + "; COMMIT");
        assertThat(result.exitCode())
                .as("migration role should retain Flyway DDL capability; output: %s", result.output())
                .isZero();
    }

    private void createLegacyRuntimeOwnedTable(String table) throws Exception {
        executeAdminSql("CREATE TABLE public." + table
                + " (id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, label text NOT NULL); "
                + "ALTER TABLE public." + table + " OWNER TO :\"runtime_username\"");
    }

    private void rerunRoleBootstrap() throws Exception {
        CommandResult result = runCompose("run", "--rm", "--no-deps", "-T", "db-roles-init");
        assertThat(result.exitCode())
                .as("idempotent role bootstrap should upgrade an existing-volume-style table; output: %s", result.output())
                .isZero();
    }

    private void assertRuntimeCanUseMigratedLegacyTable(String table) throws Exception {
        CommandResult insert = runRuntimePsql("INSERT INTO public." + table + " (label) VALUES ('runtime-write') "
                + "RETURNING label");
        assertThat(insert.exitCode())
                .as("runtime should retain INSERT and sequence usage after ownership transfer: %s", insert.output())
                .isZero();
        assertThat(insert.output()).contains("runtime-write");
        CommandResult update = runRuntimePsql("UPDATE public." + table + " SET label = 'runtime-updated' "
                + "WHERE label = 'runtime-write'");
        assertThat(update.exitCode()).isZero();
        assertThat(update.output()).contains("UPDATE 1");
        CommandResult selected = runRuntimePsql(
                "SELECT label FROM public." + table + " WHERE label = 'runtime-updated'"
        );
        assertThat(selected.exitCode()).isZero();
        assertThat(selected.output()).contains("runtime-updated");
        CommandResult deleted = runRuntimePsql(
                "DELETE FROM public." + table + " WHERE label = 'runtime-updated'"
        );
        assertThat(deleted.exitCode()).isZero();
        assertThat(deleted.output()).contains("DELETE 1");
    }

    private void exerciseBackendRuntimeDml(String adminToken, String policyName, String suffix) throws Exception {
        ObjectNode createBody = policyBody(policyName, "phase14-resource-" + suffix, "ALLOW");
        HttpResult created = request("POST", "/api/policies", adminToken, createBody);
        assertStatus(created, 201);
        long policyId = created.body().path("id").asLong();
        assertThat(policyId).isPositive();

        ObjectNode updateBody = policyBody(policyName, "phase14-resource-" + suffix, "DENY");
        HttpResult updated = request("PUT", "/api/policies/" + policyId, adminToken, updateBody);
        assertStatus(updated, 200);
        assertThat(updated.body().path("effect").asText()).isEqualTo("DENY");

        HttpResult fetched = request("GET", "/api/policies/" + policyId, adminToken, null);
        assertStatus(fetched, 200);
        assertThat(fetched.body().path("effect").asText()).isEqualTo("DENY");

        assertStatus(request("DELETE", "/api/policies/" + policyId, adminToken, null), 204);
    }

    private void assertRuntimeCannotMutateAppendOnlyAudit(String policyName) throws Exception {
        String escapedName = policyName.replace("'", "''");
        assertSqlRejected(runRuntimePsql("UPDATE public.policy_change_audits SET after_name = after_name "
                        + "WHERE after_name = '" + escapedName + "'"),
                "updating append-only audit history", "audit history is append-only");
        assertSqlRejected(runRuntimePsql("DELETE FROM public.policy_change_audits "
                        + "WHERE after_name = '" + escapedName + "'"),
                "deleting append-only audit history", "audit history is append-only");
        assertThat(query("SELECT count(*) FROM public.policy_change_audits WHERE after_name = '" + escapedName + "'"))
                .as("runtime attempts must not modify CREATE/UPDATE audit history")
                .isEqualTo("2");
    }

    private ObjectNode policyBody(String name, String resource, String effect) {
        return JSON.createObjectNode()
                .put("name", name)
                .put("subject", "SENSOR")
                .put("resource", resource)
                .put("action", "READ")
                .put("effect", effect)
                .put("enabled", true)
                .put("description", "Phase 14 runtime privilege integration fixture");
    }

    private String login(String username, String password) throws Exception {
        HttpResult result = request("POST", "/api/auth/login", null,
                JSON.createObjectNode().put("username", username).put("password", password));
        assertStatus(result, 200);
        String token = result.body().path("accessToken").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private HttpResult request(String method, String path, String token, JsonNode body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(HTTP_TIMEOUT)
                .header("Accept", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode responseBody;
        if (response.body() == null || response.body().isBlank()) {
            responseBody = JSON.getNodeFactory().nullNode();
        } else {
            try {
                responseBody = JSON.readTree(response.body());
            } catch (Exception ignored) {
                responseBody = JSON.getNodeFactory().textNode(response.body());
            }
        }
        return new HttpResult(response.statusCode(), responseBody);
    }

    private void executeAdminSql(String sql) throws Exception {
        CommandResult result = runAdminPsql(sql);
        assertThat(result.exitCode())
                .as("administrative PostgreSQL command should succeed; output: %s", result.output())
                .isZero();
    }

    private String query(String sql) throws Exception {
        CommandResult result = runAdminPsqlQuery(sql);
        assertThat(result.exitCode())
                .as("PostgreSQL query should succeed; output: %s", result.output())
                .isZero();
        return result.output().trim();
    }

    private CommandResult runAdminPsql(String sql) throws Exception {
        return runComposeShell(ADMIN_PSQL_COMMAND, "phase14-db-role-test", sql);
    }

    private CommandResult runAdminPsqlQuery(String sql) throws Exception {
        return runComposeShell(ADMIN_PSQL_QUERY_COMMAND, "phase14-db-role-test", sql);
    }

    private CommandResult runRuntimePsql(String sql) throws Exception {
        return runComposeShell(RUNTIME_PSQL_COMMAND, "phase14-runtime-role-test", sql);
    }

    private CommandResult runMigrationPsql(String sql) throws Exception {
        return runComposeShell(MIGRATION_PSQL_COMMAND, "phase14-migration-role-test", sql);
    }

    private CommandResult runComposeShell(String command, String shellName, String sql) throws Exception {
        return runCompose("exec", "-T", "postgres", "sh", "-c", command, shellName, sql);
    }

    private CommandResult runCompose(String... arguments) throws Exception {
        ProcessBuilder builder = new ProcessBuilder();
        builder.command("docker", "compose");
        builder.command().addAll(java.util.List.of(arguments));
        builder.directory(repositoryRoot().toFile()).redirectErrorStream(true);
        Process process = builder.start();
        if (!process.waitFor(45, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Timed out waiting for Docker Compose PostgreSQL command");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new CommandResult(process.exitValue(), output);
    }

    private Path repositoryRoot() throws IOException {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (candidate != null && !Files.exists(candidate.resolve("docker-compose.yml"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IOException("Could not locate docker-compose.yml from the test working directory");
        }
        return candidate;
    }

    private static void assertSqlRejected(CommandResult result, String operation, String reason) {
        assertThat(result.exitCode())
                .as("runtime should be unable to perform %s; output: %s", operation, result.output())
                .isNotZero();
        assertThat(result.output().toLowerCase(Locale.ROOT))
                .as("PostgreSQL should reject %s for the expected reason", operation)
                .contains(reason.toLowerCase(Locale.ROOT));
    }

    private static void assertStatus(HttpResult result, int expectedStatus) {
        assertThat(result.status())
                .as("HTTP status; body: %s", result.body())
                .isEqualTo(expectedStatus);
    }

    private static String environment(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String requiredEnvironment(String primary, String fallback) {
        String value = System.getenv(primary);
        if (value == null || value.isBlank()) {
            value = System.getenv(fallback);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Set " + primary + " or " + fallback + " for Phase 14 integration checks");
        }
        return value;
    }

    private record HttpResult(int status, JsonNode body) {
    }

    private record CommandResult(int exitCode, String output) {
    }
}
