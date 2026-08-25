// =============================================================================
// modules/sql.bicep - Azure SQL server and database
// -----------------------------------------------------------------------------
// Cheapest usable configuration for a short lived PoC:
//   Basic  5 DTU, 2 GB, locally redundant backup. Around 0,006 EUR per hour.
//   S0     10 DTU, use it if Basic throttles the workload.
//   GP_S_Gen5_1  serverless, auto pause after one hour of inactivity. More
//                expensive while active but it stops billing compute when idle.
//
// This service uses ddl-auto: none, so the schema is applied by the pipeline
// from src/main/resources/database.sql. It is not created by Hibernate.
// =============================================================================

param location string
param namePrefix string
param tags object
param sqlSkuName string
param databaseName string
param administratorLogin string

@secure()
param administratorPassword string

param logAnalyticsWorkspaceId string

@description('Send the diagnostic settings of the database to Log Analytics. Turn it off when the Azure Native New Relic Service already forwards them, to avoid paying twice for the same data')
param enableLogAnalytics bool = true

@description('Enable Azure SQL Auditing. The diagnostic categories the database publishes by default only report problems (errors, timeouts, blocks, deadlocks), so a query that succeeds produces no log at all. Auditing is the only per statement log the engine itself emits. Off by default because it is verbose and every record counts against the daily ingestion cap')
param enableSqlAudit bool = false

// Basic is capped at 2 GB by the service. The serverless option gets a larger
// cap because its storage is billed per GB actually used.
var maxSizeBytes = sqlSkuName == 'Basic' ? 2147483648 : 34359738368

var skuMap = {
  Basic: {
    name: 'Basic'
    tier: 'Basic'
    capacity: 5
  }
  S0: {
    name: 'S0'
    tier: 'Standard'
    capacity: 10
  }
  GP_S_Gen5_1: {
    name: 'GP_S_Gen5_1'
    tier: 'GeneralPurpose'
    family: 'Gen5'
    capacity: 1
  }
}

resource sqlServer 'Microsoft.Sql/servers@2023-08-01-preview' = {
  name: 'sql-${namePrefix}-${uniqueString(resourceGroup().id)}'
  location: location
  tags: tags
  properties: {
    administratorLogin: administratorLogin
    administratorLoginPassword: administratorPassword
    version: '12.0'
    minimalTlsVersion: '1.2'
    publicNetworkAccess: 'Enabled'
  }
}

// The web app runs on the shared App Service infrastructure without VNet
// integration, so its outbound address is not predictable. The special
// 0.0.0.0 rule allows only traffic originated inside Azure, not the internet.
resource allowAzureServices 'Microsoft.Sql/servers/firewallRules@2023-08-01-preview' = {
  parent: sqlServer
  name: 'AllowAllWindowsAzureIps'
  properties: {
    startIpAddress: '0.0.0.0'
    endIpAddress: '0.0.0.0'
  }
}

var baseDatabaseProperties = {
  collation: 'SQL_Latin1_General_CP1_CI_AS'
  maxSizeBytes: maxSizeBytes
  zoneRedundant: false
  // Locally redundant backup is the cheapest option and enough for a PoC.
  requestedBackupStorageRedundancy: 'Local'
}

// Serverless only: pause the compute after one hour without connections.
var serverlessProperties = sqlSkuName == 'GP_S_Gen5_1' ? {
  autoPauseDelay: 60
  minCapacity: json('0.5')
} : {}

resource database 'Microsoft.Sql/servers/databases@2023-08-01-preview' = {
  parent: sqlServer
  name: databaseName
  location: location
  tags: tags
  sku: skuMap[sqlSkuName]
  properties: union(baseDatabaseProperties, serverlessProperties)
}

// Azure Monitor: query performance, errors, timeouts, deadlocks and blocks.
//
// The condition includes enableSqlAudit on purpose. This setting is also the
// sink of the audit trail, because allLogs carries SQLSecurityAuditEvents, so
// turning Log Analytics off while auditing is on would leave the audit with no
// destination and it would fail silently, which is exactly the failure mode the
// Azure documentation warns about.
resource databaseDiagnostics 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = if (enableLogAnalytics || enableSqlAudit) {
  name: 'diag-${databaseName}'
  scope: database
  properties: {
    workspaceId: logAnalyticsWorkspaceId
    // Tablas dedicadas. El valor por defecto es AzureDiagnostics, y con el TODO
    // acaba en la tabla generica AzureDiagnostics: la tabla
    // SQLSecurityAuditEvents se queda vacia y las columnas pierden su nombre
    // (Statement pasa a statement_s, ClientIp a client_ip_s...). Sin esta linea
    // la auditoria se genera pero no se puede consultar como esta documentado.
    logAnalyticsDestinationType: 'Dedicated'
    logs: [
      {
        categoryGroup: 'allLogs'
        enabled: true
      }
    ]
    metrics: [
      {
        // DTU, storage, sessions, workers, deadlocks, availability and the
        // connection counters (successful, failed, blocked by firewall).
        category: 'Basic'
        enabled: true
      }
      {
        // Engine level counters that Basic does not carry: cpu and memory of
        // the SQL instance and tempdb usage. WorkloadManagement is the third
        // category and is deliberately left out: its wlg_* metrics only apply
        // to data warehouses, not to a single database.
        category: 'InstanceAndAppAdvanced'
        enabled: true
      }
    ]
  }
}

// The only per statement log Azure SQL emits. It is written by the engine, not
// by the application, so it also catches whatever connects to the database from
// outside this service.
//
// isAzureMonitorTargetEnabled routes the records to the diagnostic settings
// instead of to a storage account, which is what keeps this free of extra
// resources.
//
// It depends on the diagnostic setting on purpose: with Azure Monitor as the
// target, the destination has to be in place before auditing is turned on.
// A single setting with categoryGroup allLogs already carries the
// SQLSecurityAuditEvents category: Azure itself proves it, because declaring a
// second setting with that category towards the same workspace is rejected with
// "Data sinks can't be reused in different settings on the same category for the
// same resource".
resource databaseAuditing 'Microsoft.Sql/servers/databases/auditingSettings@2023-08-01-preview' = if (enableSqlAudit) {
  parent: database
  name: 'default'
  dependsOn: [
    databaseDiagnostics
  ]
  properties: {
    state: 'Enabled'
    isAzureMonitorTargetEnabled: true
    auditActionsAndGroups: [
      // One record per statement batch executed against the database. This is
      // what makes the schema visible: here the DDL comes from the pipeline
      // applying database.sql, not from the application, and it is just another
      // batch.
      'BATCH_COMPLETED_GROUP'
      // Connections: who reached the database and who failed to.
      'SUCCESSFUL_DATABASE_AUTHENTICATION_GROUP'
      'FAILED_DATABASE_AUTHENTICATION_GROUP'
      // Explicit records for structural changes. Low volume, and they answer
      // "who altered this table" without digging through every batch.
      'SCHEMA_OBJECT_CHANGE_GROUP'
      'DATABASE_OBJECT_CHANGE_GROUP'
      // Who created a user or a role, and who granted what. Rare events, and
      // the ones that matter most in an audit.
      'DATABASE_PRINCIPAL_CHANGE_GROUP'
      'DATABASE_ROLE_MEMBER_CHANGE_GROUP'
      'DATABASE_PERMISSION_CHANGE_GROUP'
      'DATABASE_OBJECT_PERMISSION_CHANGE_GROUP'
    ]
  }
}

output serverName string = sqlServer.name
output serverFqdn string = sqlServer.properties.fullyQualifiedDomainName
output databaseName string = database.name
output databaseId string = database.id
