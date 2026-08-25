// =============================================================================
// main.bicepparam
// -----------------------------------------------------------------------------
// Every value is read from the environment so nothing sensitive is written in
// a versioned file.
//
// All readEnvironmentVariable calls declare a default, including the secrets.
// A missing variable must NOT be a compile error: Bicep resolves these calls
// at compile time, so a call without a default breaks the editor,
// az bicep build-params and az deployment what-if.
//
// The presence check lives in the pipeline: deploy.yml verifies every required
// secret and fails with an explicit message before calling Azure. For a manual
// deployment, export the variables first or the deployment will be rejected by
// Azure itself (for example, an empty SQL administrator password).
//
// Usage (the "using" statement below already points to the template, so
// --template-file must not be passed):
//   az deployment sub create \
//     --location westeurope \
//     --parameters infra/main.bicepparam
// =============================================================================

using './main.bicep'

param location = readEnvironmentVariable('AZURE_LOCATION', 'westeurope')
param namePrefix = readEnvironmentVariable('POC_NAME_PREFIX', 'ordersvc')
param resourceGroupName = readEnvironmentVariable('AZURE_RESOURCE_GROUP', 'rg-ordersvc')
param owner = readEnvironmentVariable('POC_OWNER', 'unknown')
param ttl = readEnvironmentVariable('POC_TTL', '1h')

param logRetentionDays = int(readEnvironmentVariable('LOG_RETENTION_DAYS', '30'))
param logDailyQuotaGb = int(readEnvironmentVariable('LOG_DAILY_QUOTA_GB', '1'))
param enableLogAnalytics = bool(readEnvironmentVariable('ENABLE_LOG_ANALYTICS', 'true'))
param enableActivityLogExport = bool(readEnvironmentVariable('ENABLE_ACTIVITY_LOG_EXPORT', 'false'))
param enableSqlAudit = bool(readEnvironmentVariable('ENABLE_SQL_AUDIT', 'true'))

param appServiceSku = readEnvironmentVariable('APP_SERVICE_SKU', 'B1')
param javaOpts = readEnvironmentVariable('JAVA_OPTS', '-Xmx512m')

param sqlSkuName = readEnvironmentVariable('SQL_SKU_NAME', 'Basic')
param sqlDatabaseName = readEnvironmentVariable('SQL_DATABASE_NAME', 'sqldb-orders')
param sqlAdminUser = readEnvironmentVariable('SQL_ADMIN_USER', '')
param sqlAdminPassword = readEnvironmentVariable('SQL_ADMIN_PASSWORD', '')

param basicAuthUser = readEnvironmentVariable('BASIC_AUTH_USER', '')
param basicAuthPassword = readEnvironmentVariable('BASIC_AUTH_PASSWORD', '')

param gatewayBaseUrl = readEnvironmentVariable('GATEWAY_BASE_URL', '')
param gatewayUsersPath = readEnvironmentVariable('GATEWAY_USERS_PATH', '/api-users/v1')
param gatewayBasicUser = readEnvironmentVariable('GATEWAY_BASIC_USER', '')
param gatewayBasicPassword = readEnvironmentVariable('GATEWAY_BASIC_PASSWORD', '')

param newRelicLicenseKey = readEnvironmentVariable('NR_LICENSE_KEY', '')
param newRelicOtlpEndpoint = readEnvironmentVariable('NR_OTLP_ENDPOINT', 'https://otlp.eu01.nr-data.net:4318')

param observabilityEnabled = bool(readEnvironmentVariable('OBSERVABILITY_ENABLED', 'true'))
param serviceName = readEnvironmentVariable('OTEL_SERVICE_NAME', 'microservice-orders')
param serviceVersion = readEnvironmentVariable('SERVICE_VERSION', '1.0.0')
param environmentName = readEnvironmentVariable('ENVIRONMENT', 'poc')
param serviceNamespace = readEnvironmentVariable('SERVICE_NAMESPACE', 'poc-observability')
param logLevel = readEnvironmentVariable('LOG_LEVEL', 'INFO')
param sqlLogLevel = readEnvironmentVariable('SQL_LOG_LEVEL', 'INFO')
