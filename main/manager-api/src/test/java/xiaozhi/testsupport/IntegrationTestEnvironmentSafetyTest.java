package xiaozhi.testsupport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IntegrationTestEnvironmentSafetyTest {
    @Test
    void acceptsOnlyExplicitLoopbackTestSchemas() {
        assertTrue(IntegrationTestEnvironment.isIsolatedJdbcUrl(
                "jdbc:mysql://127.0.0.1:34776/xiaozhi_full_test_full_fixture?useSSL=false"));
        assertTrue(IntegrationTestEnvironment.isIsolatedJdbcUrl(
                "jdbc:mysql://localhost/xiaozhi_full_test_fixture"));
    }

    @Test
    void refusesApplicationSchemasAndRemoteOrMissingTargets() {
        assertFalse(IntegrationTestEnvironment.isIsolatedJdbcUrl(null));
        assertFalse(IntegrationTestEnvironment.isIsolatedJdbcUrl(""));
        assertFalse(IntegrationTestEnvironment.isIsolatedJdbcUrl(
                "jdbc:mysql://127.0.0.1:3306/xiaozhi_esp32_server"));
        assertFalse(IntegrationTestEnvironment.isIsolatedJdbcUrl(
                "jdbc:mysql://database.example.invalid/xiaozhi_full_test_fixture"));
        assertFalse(IntegrationTestEnvironment.isIsolatedJdbcUrl(
                "jdbc:mysql://127.0.0.1/xiaozhi_full_test_fixture/another_schema"));
    }
}
