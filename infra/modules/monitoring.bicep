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
    // S6329: acceso publico de red. Es DELIBERADO y necesario aqui:
    //  - el pipeline consulta el workspace con "az monitor log-analytics query"
    //    desde un runner de GitHub, que esta fuera de cualquier VNet;
    //  - la ingesta llega desde recursos gestionados de Azure.
    // Cerrarlo exige Private Link mas un runner autohospedado dentro de la VNet,
    // fuera del alcance de un PoC de una hora. Documentado como limitacion
    // conocida en el README.
    publicNetworkAccessForIngestion: 'Enabled'
    publicNetworkAccessForQuery: 'Enabled'
  }
}

output workspaceId string = logAnalytics.id
output workspaceName string = logAnalytics.name
output workspaceCustomerId string = logAnalytics.properties.customerId
