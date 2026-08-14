// =============================================================================
// modules/appservice.bicep - Linux App Service plan and web app
// -----------------------------------------------------------------------------
// Runs the Spring Boot jar on the Java SE 17 blessed image through
// startup.sh, which attaches the OpenTelemetry Java agent. The application
// code carries no instrumentation (zero-code).
//
// Every OTEL_* setting below is the configuration New Relic documents for its
// OTLP endpoint. Changing account or region only requires changing the
// endpoint and the license key.
// =============================================================================

param location string
param namePrefix string
param tags object
param appServiceSku string
param javaOpts string
param logAnalyticsWorkspaceId string

param sqlServerName string
param sqlServerFqdn string
param sqlDatabaseName string
param sqlAdminUser string

@secure()
param sqlAdminPassword string

@secure()
param apiUser string

@secure()
param apiPassword string

// This service calls the users microservice to validate the owner of an order.
param usersServiceUrl string

@secure()
param usersServiceUser string

@secure()
param usersServicePassword string

@secure()
param newRelicLicenseKey string

param newRelicOtlpEndpoint string
param observabilityEnabled bool
param serviceName string
param serviceVersion string
param environmentName string
param serviceNamespace string
param logLevel string

@description('Log level of the Hibernate SQL logger. DEBUG ships every executed statement to New Relic as a log record')
param sqlLogLevel string = 'INFO'

@description('Tag name that excludes a resource from the platform log forwarding of the Azure Native New Relic Service')
param logExclusionTagName string = 'newrelicLogs'

@description('Tag value that excludes a resource from the platform log forwarding')
param logExclusionTagValue string = 'exclude'

@description('Send the diagnostic settings of this web app to Log Analytics. Turn it off when the Azure Native New Relic Service already forwards them, to avoid paying twice for the same data')
param enableLogAnalytics bool = true

// F1 is the free tier: no Always On, no health check and a quota of 60 CPU
// minutes per day. Every other SKU here belongs to the Basic tier.
var isFreeTier = appServiceSku == 'F1'

var skuTier = isFreeTier ? 'Free' : 'Basic'

// -----------------------------------------------------------------------------
// Application settings
// -----------------------------------------------------------------------------

var platformSettings = [
  {
    // The application reads server.port from PORT.
    name: 'PORT'
    value: '8080'
  }
  {
    // The deployment package already contains the built jar.
    name: 'SCM_DO_BUILD_DURING_DEPLOYMENT'
    value: 'false'
  }
  {
    // Memory flags only. The agent is deliberately NOT declared here: the JVM
    // applies JAVA_TOOL_OPTIONS unconditionally, so while the package is not
    // deployed yet the process cannot even start ("agent library failed to
    // init"). startup.sh attaches the agent after checking that the jar is
    // actually there.
    name: 'JAVA_TOOL_OPTIONS'
    value: javaOpts
  }
  {
    name: 'ENVIRONMENT'
    value: environmentName
  }
  {
    name: 'LOG_LEVEL'
    value: logLevel
  }
  {
    // Read by application.yml as the level of the org.hibernate.SQL logger.
    // Set it to DEBUG to ship every executed statement as a log record, which
    // travels to New Relic through the Logback instrumentation of the agent.
    // It is NOT named LOGGING_LEVEL_ORG_HIBERNATE_SQL because Spring Boot
    // relaxed binding lowercases environment variable names, and the logger
    // "org.hibernate.sql" is not the logger "org.hibernate.SQL".
    name: 'SQL_LOG_LEVEL'
    value: sqlLogLevel
  }
]

// The application builds its JDBC url from these values. SQL_SERVER_NAME is
// the short server name required by the "user@server" login format, and
// SQL_SERVER_PORT is interpolated in the url as well.
var databaseSettings = [
  {
    name: 'SQL_SERVER'
    value: sqlServerFqdn
  }
  {
    name: 'SQL_SERVER_NAME'
    value: sqlServerName
  }
  {
    name: 'SQL_SERVER_PORT'
    value: '1433'
  }
  {
    name: 'SQL_DATABASE'
    value: sqlDatabaseName
  }
  {
    name: 'SQL_USERNAME'
    value: sqlAdminUser
  }
  {
    name: 'SQL_PASSWORD'
    value: sqlAdminPassword
  }
]

var securitySettings = [
  {
    name: 'API_USERNAME'
    value: apiUser
  }
  {
    name: 'API_PASSWORD'
    value: apiPassword
  }
]

// Downstream dependency: the users microservice.
var usersServiceSettings = [
  {
    name: 'USERS_SERVICE_URL'
    value: usersServiceUrl
  }
  {
    name: 'USERS_SERVICE_USERNAME'
    value: usersServiceUser
  }
  {
    name: 'USERS_SERVICE_PASSWORD'
    value: usersServicePassword
  }
]

var otelEnabledSettings = [
  {
    name: 'OTEL_SERVICE_NAME'
    value: serviceName
  }
  {
    name: 'OTEL_RESOURCE_ATTRIBUTES'
    value: 'service.name=${serviceName},service.version=${serviceVersion},service.namespace=${serviceNamespace},deployment.environment=${environmentName},cloud.provider=azure'
  }
  {
    name: 'OTEL_EXPORTER_OTLP_ENDPOINT'
    value: newRelicOtlpEndpoint
  }
  {
    name: 'OTEL_EXPORTER_OTLP_HEADERS'
    value: 'api-key=${newRelicLicenseKey}'
  }
  {
    name: 'OTEL_EXPORTER_OTLP_PROTOCOL'
    value: 'http/protobuf'
  }
  {
    name: 'OTEL_EXPORTER_OTLP_COMPRESSION'
    value: 'gzip'
  }
  {
    name: 'OTEL_TRACES_EXPORTER'
    value: 'otlp'
  }
  {
    name: 'OTEL_METRICS_EXPORTER'
    value: 'otlp'
  }
  {
    name: 'OTEL_LOGS_EXPORTER'
    value: 'otlp'
  }
  {
    // New Relic ingests delta temporality.
    name: 'OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE'
    value: 'delta'
  }
  {
    // Exponential histograms keep percentiles accurate with less data.
    name: 'OTEL_EXPORTER_OTLP_METRICS_DEFAULT_HISTOGRAM_AGGREGATION'
    value: 'base2_exponential_bucket_histogram'
  }
  {
    // New Relic drops attributes longer than 4095 characters.
    name: 'OTEL_ATTRIBUTE_VALUE_LENGTH_LIMIT'
    value: '4095'
  }
  {
    name: 'OTEL_EXPERIMENTAL_EXPORTER_OTLP_RETRY_ENABLED'
    value: 'true'
  }
  {
    // process.command_args can exceed the attribute limit and is not useful.
    name: 'OTEL_EXPERIMENTAL_RESOURCE_DISABLED_KEYS'
    value: 'process.command_args'
  }
  {
    name: 'OTEL_SEMCONV_STABILITY_OPT_IN'
    value: 'http'
  }
  {
    name: 'OTEL_METRIC_EXPORT_INTERVAL'
    value: '30000'
  }
  {
    name: 'OTEL_TRACES_SAMPLER'
    value: 'parentbased_always_on'
  }
  {
    // Sends the SLF4J key value pairs as structured attributes.
    name: 'OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_CAPTURE_KEY_VALUE_PAIR_ATTRIBUTES'
    value: 'true'
  }
  {
    // Sends every MDC entry, which carries the trace and span ids.
    name: 'OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_CAPTURE_MDC_ATTRIBUTES'
    value: '*'
  }
  {
    // Routes the agent own logs through Logback so they also reach New Relic.
    name: 'OTEL_JAVAAGENT_LOGGING'
    value: 'application'
  }
  // ---------------------------------------------------------------------
  // Database telemetry over OTLP
  // ---------------------------------------------------------------------
  {
    // Spans for every JDBC statement, with the sql text sanitized.
    name: 'OTEL_INSTRUMENTATION_COMMON_DB_STATEMENT_SANITIZER_ENABLED'
    value: 'true'
  }
  {
    // Off by default: adds a span for DataSource.getConnection, which is how
    // connection pool waits become visible in a trace.
    name: 'OTEL_INSTRUMENTATION_JDBC_DATASOURCE_ENABLED'
    value: 'true'
  }
  {
    // Bridges Micrometer to OTLP, so the Actuator metrics of the connection
    // pool (hikaricp.connections.*, jdbc.connections.*) reach New Relic as
    // metrics instead of staying inside the app.
    name: 'OTEL_INSTRUMENTATION_MICROMETER_ENABLED'
    value: 'true'
  }
]

var otelDisabledSettings = [
  {
    // The agent is still attached, but it disables itself.
    name: 'OTEL_JAVAAGENT_ENABLED'
    value: 'false'
  }
  {
    name: 'OTEL_TRACES_EXPORTER'
    value: 'none'
  }
  {
    name: 'OTEL_METRICS_EXPORTER'
    value: 'none'
  }
  {
    name: 'OTEL_LOGS_EXPORTER'
    value: 'none'
  }
]

var appSettings = concat(
  platformSettings,
  databaseSettings,
  securitySettings,
  usersServiceSettings,
  observabilityEnabled ? otelEnabledSettings : otelDisabledSettings
)

// -----------------------------------------------------------------------------
// Site configuration
// -----------------------------------------------------------------------------

// Application settings and connection strings are NOT declared here. They are
// applied as dedicated child resources (see below), which is the only way to
// make the collection authoritative: settings declared inside siteConfig are
// merged by the platform, so a value changed in GitHub would not always
// overwrite the value already present in the web app.
var baseSiteConfig = {
  linuxFxVersion: 'JAVA|17-java17'
  // startup.sh travels in the deployment package and attaches the agent only
  // if the jar is present, so a half deployed wwwroot does not stop the app.
  appCommandLine: 'bash /home/site/wwwroot/startup.sh'
  minTlsVersion: '1.2'
  scmMinTlsVersion: '1.2'
  ftpsState: 'Disabled'
  http20Enabled: true
}

// Always On and the health check are not available on the free tier.
var tierSiteConfig = isFreeTier ? {} : {
  alwaysOn: true
  healthCheckPath: '/actuator/health'
}

// =============================================================================
// Resources
// =============================================================================

resource plan 'Microsoft.Web/serverfarms@2023-12-01' = {
  name: 'plan-${namePrefix}'
  location: location
  tags: tags
  kind: 'linux'
  sku: {
    name: appServiceSku
    tier: skuTier
    capacity: 1
  }
  properties: {
    reserved: true
  }
}

// Deduplication. The application logs of this web app already travel to New
// Relic through the OpenTelemetry agent, correlated with their traces. This tag
// keeps the Azure Native New Relic Service from forwarding the same lines a
// second time as platform logs. Its platform metrics are still collected,
// because those the agent cannot see.
var siteTags = union(tags, {
  '${logExclusionTagName}': logExclusionTagValue
})

resource site 'Microsoft.Web/sites@2023-12-01' = {
  name: 'app-${namePrefix}-${uniqueString(resourceGroup().id)}'
  location: location
  tags: siteTags
  kind: 'app,linux'
  identity: {
    // Enables Key Vault references and passwordless database access later on.
    type: 'SystemAssigned'
  }
  properties: {
    serverFarmId: plan.id
    httpsOnly: true
    clientAffinityEnabled: false
    siteConfig: union(baseSiteConfig, tierSiteConfig)
  }
}

// Authoritative application settings. Declaring them as a child resource
// replaces the whole collection on every deployment, so a value changed in a
// GitHub secret or variable always reaches the running app. The web app is
// recycled automatically when this resource changes.
resource siteAppSettings 'Microsoft.Web/sites/config@2023-12-01' = {
  parent: site
  name: 'appsettings'
  properties: toObject(appSettings, setting => setting.name, setting => setting.value)
}

// Explicit link between the web app and the database. The application builds
// its own url from the settings above, this entry makes the dependency visible
// in the portal and to Service Connector.
resource siteConnectionStrings 'Microsoft.Web/sites/config@2023-12-01' = {
  parent: site
  name: 'connectionstrings'
  properties: {
    SqlDatabase: {
      type: 'SQLAzure'
      value: 'Server=tcp:${sqlServerFqdn},1433;Initial Catalog=${sqlDatabaseName};Persist Security Info=False;User ID=${sqlAdminUser};Password=${sqlAdminPassword};MultipleActiveResultSets=False;Encrypt=True;TrustServerCertificate=False;Connection Timeout=30;'
    }
  }
  // Two config children written at the same time make App Service answer 409.
  dependsOn: [
    siteAppSettings
  ]
}

// File system logging must be on for the console and application log
// categories to reach the workspace.
resource siteLogs 'Microsoft.Web/sites/config@2023-12-01' = {
  parent: site
  name: 'logs'
  dependsOn: [
    siteConnectionStrings
  ]
  properties: {
    applicationLogs: {
      fileSystem: {
        level: 'Information'
      }
    }
    httpLogs: {
      fileSystem: {
        retentionInMb: 35
        retentionInDays: 1
        enabled: true
      }
    }
    detailedErrorMessages: {
      enabled: true
    }
    failedRequestsTracing: {
      enabled: false
    }
  }
}

// Azure Monitor: HTTP logs, console logs, application logs, platform logs and
// audit logs of the web app.
resource siteDiagnostics 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = if (enableLogAnalytics) {
  name: 'diag-${namePrefix}-app'
  scope: site
  properties: {
    workspaceId: logAnalyticsWorkspaceId
    logs: [
      {
        categoryGroup: 'allLogs'
        enabled: true
      }
    ]
    metrics: [
      {
        category: 'AllMetrics'
        enabled: true
      }
    ]
  }
}

// =============================================================================
// Outputs
// =============================================================================

output webAppName string = site.name
output webAppUrl string = 'https://${site.properties.defaultHostName}'
output webAppHostName string = site.properties.defaultHostName
output principalId string = site.identity.principalId
output planName string = plan.name
