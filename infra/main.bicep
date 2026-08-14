// =============================================================================
// main.bicep - PoC microservice-orders on Azure App Service + Azure SQL
// -----------------------------------------------------------------------------
// Scope: subscription. Creates the resource group and everything inside it:
//   - Log Analytics workspace (Azure Monitor sink)
//   - Azure SQL server + database (cheapest usable tier)
//   - Linux App Service plan + web app running the Spring Boot jar with the
//     OpenTelemetry Java agent exporting to New Relic
//   - Diagnostic settings for the web app and for the database, which can be
//     turned off with enableLogAnalytics when the Azure Native New Relic
//     Service already forwards the platform logs
//
// The database schema is NOT created by this template nor by Hibernate: this
// service runs with ddl-auto: none, so the pipeline applies
// src/main/resources/database.sql after the deployment.
//
// No secret is written in this file: every credential arrives as a @secure()
// parameter fed from GitHub Secrets.
// =============================================================================

targetScope = 'subscription'

@description('Azure region for every resource')
param location string = 'westeurope'

@description('Prefix used to build every resource name')
@minLength(3)
@maxLength(12)
param namePrefix string = 'ordersvc'

@description('Name of the resource group that holds the whole PoC')
param resourceGroupName string = 'rg-${namePrefix}'

@description('Owner tag, used for cost attribution and cleanup')
param owner string = 'unknown'

@description('Expected lifetime of the PoC, used by the cleanup automation')
param ttl string = '1h'

@description('Deployment timestamp. Read by the scheduled cleanup. Leave the default')
param createdAt string = utcNow('yyyy-MM-ddTHH:mm:ssZ')

// -----------------------------------------------------------------------------
// Monitoring
// -----------------------------------------------------------------------------

@description('Log Analytics retention in days. 30 is the minimum billable value')
@minValue(30)
@maxValue(730)
param logRetentionDays int = 30

@description('Daily ingestion cap in GB. Protects the PoC budget')
param logDailyQuotaGb int = 1

@description('Send diagnostic settings to Log Analytics. Set it to false once the Azure Native New Relic Service forwards the platform logs, so the same data is not ingested (and paid for) twice')
param enableLogAnalytics bool = true

@description('Export the subscription Activity Log to the workspace. Off by default because it is a subscription wide change')
param enableActivityLogExport bool = false

@description('Enable Azure SQL Auditing, the only per statement log the database engine emits. Without it a successful query leaves no trace in Azure Monitor, because the default categories only report errors, timeouts, blocks and deadlocks. Off by default: it is verbose and every record counts against the daily ingestion cap')
param enableSqlAudit bool = false

// -----------------------------------------------------------------------------
// Compute
// -----------------------------------------------------------------------------

@description('App Service plan SKU. F1 is free but has a 60 CPU-minutes per day quota, 1 GB of RAM, no Always On and no health check. B1 is the cheapest tier that runs a Java agent comfortably')
@allowed([
  'F1'
  'B1'
  'B2'
])
param appServiceSku string = 'B1'

@description('JVM flags applied through JAVA_TOOL_OPTIONS. Do NOT put -javaagent here: the JVM applies these flags always, and a missing agent jar would stop the app from starting at all. startup.sh attaches the agent')
param javaOpts string = '-Xmx512m'

// -----------------------------------------------------------------------------
// Database
// -----------------------------------------------------------------------------

@description('SQL database SKU. Basic is 5 DTU and 2 GB, the cheapest provisioned option')
@allowed([
  'Basic'
  'S0'
  'GP_S_Gen5_1'
])
param sqlSkuName string = 'Basic'

@description('Database name')
param sqlDatabaseName string = 'sqldb-orders'

// Note on validation: the parameters below are fed from environment variables
// through main.bicepparam, and Bicep evaluates readEnvironmentVariable at
// compile time. A @minLength decorator here would turn a missing variable into
// a compile error that also breaks the editor and az deployment what-if.
// The presence check therefore lives in the pipeline, which fails with an
// explicit message before calling Azure.
@description('SQL administrator login. Cannot be admin, administrator, sa, root, dbmanager or loginmanager')
param sqlAdminUser string

@description('SQL administrator password. At least 8 characters with three of: upper case, lower case, digit, symbol')
@secure()
param sqlAdminPassword string

// -----------------------------------------------------------------------------
// Application
// -----------------------------------------------------------------------------

@description('Basic Auth user accepted by this API')
@secure()
param apiUser string

@description('Basic Auth password accepted by this API')
@secure()
param apiPassword string

@description('Base url of the users microservice, without trailing slash')
param usersServiceUrl string

@description('Basic Auth user presented to the users microservice')
@secure()
param usersServiceUser string

@description('Basic Auth password presented to the users microservice')
@secure()
param usersServicePassword string

@description('New Relic ingest license key')
@secure()
param newRelicLicenseKey string

@description('New Relic OTLP endpoint. EU accounts use https://otlp.eu01.nr-data.net:4318')
param newRelicOtlpEndpoint string = 'https://otlp.eu01.nr-data.net:4318'

@description('Turns the OpenTelemetry export on or off without touching code')
param observabilityEnabled bool = true

@description('Logical service name reported to New Relic')
param serviceName string = 'microservice-orders'

@description('Service version reported to New Relic')
param serviceVersion string = '1.0.0'

@description('deployment.environment attribute')
param environmentName string = 'poc'

@description('service.namespace attribute, shared by every service of the PoC')
param serviceNamespace string = 'poc-observability'

@description('Root log level of the application')
param logLevel string = 'INFO'

@description('Log level of the Hibernate SQL logger. Set it to DEBUG to send every executed statement to New Relic as a log record')
param sqlLogLevel string = 'INFO'

// =============================================================================
// Resources
// -----------------------------------------------------------------------------
// The tags drive cost attribution and the scheduled cleanup: createdAt is read
// by destroy.yml to decide which resource groups have expired.
// =============================================================================

var tags = {
  environment: 'poc'
  ttl: ttl
  owner: owner
  project: 'poc-microservice-orders'
  managedBy: 'bicep'
  createdAt: createdAt
}

resource rg 'Microsoft.Resources/resourceGroups@2024-03-01' = {
  name: resourceGroupName
  location: location
  tags: tags
}

module monitoring './modules/monitoring.bicep' = {
  name: 'monitoring'
  scope: rg
  params: {
    location: location
    namePrefix: namePrefix
    tags: tags
    logRetentionDays: logRetentionDays
    logDailyQuotaGb: logDailyQuotaGb
  }
}

module sql './modules/sql.bicep' = {
  name: 'sql'
  scope: rg
  params: {
    location: location
    namePrefix: namePrefix
    tags: tags
    sqlSkuName: sqlSkuName
    databaseName: sqlDatabaseName
    administratorLogin: sqlAdminUser
    administratorPassword: sqlAdminPassword
    logAnalyticsWorkspaceId: monitoring.outputs.workspaceId
    enableLogAnalytics: enableLogAnalytics
    enableSqlAudit: enableSqlAudit
  }
}

module app './modules/appservice.bicep' = {
  name: 'appservice'
  scope: rg
  params: {
    location: location
    namePrefix: namePrefix
    tags: tags
    appServiceSku: appServiceSku
    javaOpts: javaOpts
    logAnalyticsWorkspaceId: monitoring.outputs.workspaceId
    enableLogAnalytics: enableLogAnalytics
    sqlServerName: sql.outputs.serverName
    sqlServerFqdn: sql.outputs.serverFqdn
    sqlDatabaseName: sql.outputs.databaseName
    sqlAdminUser: sqlAdminUser
    sqlAdminPassword: sqlAdminPassword
    apiUser: apiUser
    apiPassword: apiPassword
    usersServiceUrl: usersServiceUrl
    usersServiceUser: usersServiceUser
    usersServicePassword: usersServicePassword
    newRelicLicenseKey: newRelicLicenseKey
    newRelicOtlpEndpoint: newRelicOtlpEndpoint
    observabilityEnabled: observabilityEnabled
    serviceName: serviceName
    serviceVersion: serviceVersion
    environmentName: environmentName
    serviceNamespace: serviceNamespace
    logLevel: logLevel
    sqlLogLevel: sqlLogLevel
  }
}

// Control plane audit trail: who created, changed or deleted what.
resource activityLogToWorkspace 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = if (enableActivityLogExport) {
  name: 'diag-activitylog-${namePrefix}'
  properties: {
    workspaceId: monitoring.outputs.workspaceId
    logs: [
      {
        category: 'Administrative'
        enabled: true
      }
      {
        category: 'Security'
        enabled: true
      }
      {
        category: 'Policy'
        enabled: true
      }
      {
        category: 'ResourceHealth'
        enabled: true
      }
    ]
  }
}

// =============================================================================
// Outputs
// =============================================================================

output resourceGroupName string = rg.name
output webAppName string = app.outputs.webAppName
output webAppUrl string = app.outputs.webAppUrl
output webAppPrincipalId string = app.outputs.principalId
output sqlServerName string = sql.outputs.serverName
output sqlServerFqdn string = sql.outputs.serverFqdn
output sqlDatabaseName string = sql.outputs.databaseName
output logAnalyticsWorkspaceName string = monitoring.outputs.workspaceName
