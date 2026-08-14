// =============================================================================
// modules/monitoring.bicep - Log Analytics workspace
// -----------------------------------------------------------------------------
// Single Azure Monitor sink for the web app logs, the database logs and,
// optionally, the subscription Activity Log.
// =============================================================================

param location string
param namePrefix string
param tags object
param logRetentionDays int
param logDailyQuotaGb int

resource logAnalytics 'Microsoft.OperationalInsights/workspaces@2023-09-01' = {
  name: 'log-${namePrefix}'
  location: location
  tags: tags
  properties: {
    sku: {
      name: 'PerGB2018'
    }
    retentionInDays: logRetentionDays
    workspaceCapping: {
      // Hard stop on ingestion so a log loop cannot generate cost.
      dailyQuotaGb: logDailyQuotaGb
    }
    publicNetworkAccessForIngestion: 'Enabled'
    publicNetworkAccessForQuery: 'Enabled'
  }
}

output workspaceId string = logAnalytics.id
output workspaceName string = logAnalytics.name
output workspaceCustomerId string = logAnalytics.properties.customerId
