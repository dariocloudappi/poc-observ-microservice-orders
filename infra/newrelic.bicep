// =============================================================================
// newrelic.bicep - Azure Native New Relic Service, one time setup
// -----------------------------------------------------------------------------
// Scope: subscription. Creates a resource group that OUTLIVES the PoC and, in
// it, the New Relic monitor resource plus its tag rules.
//
// This is deployed ONCE per subscription and per New Relic organization. Do
// not deploy it from more than one repository: two monitor resources linked to
// the same New Relic organization forward the same logs twice, which is both
// duplicated data and duplicated ingest cost.
//
// The PoC resource groups are created and destroyed around it. Azure attaches
// the diagnostic settings to the new resources on its own, by tag rules.
// =============================================================================

targetScope = 'subscription'

@description('Azure region for the monitor resource')
param location string = 'westeurope'

@description('Resource group that holds the New Relic monitor. It must survive the destroy workflow of the PoC')
param resourceGroupName string = 'rg-newrelic-shared'

@description('Name of the New Relic monitor resource')
param monitorName string = 'newrelic-poc-observability'

param owner string = 'unknown'

// -----------------------------------------------------------------------------
// New Relic account to link
// -----------------------------------------------------------------------------

@description('New Relic account id')
param newRelicAccountId string

@description('New Relic organization id')
param newRelicOrganizationId string

@allowed([
  'us'
  'eu'
])
param newRelicRegion string = 'eu'

@description('Ingest license key of the account')
@secure()
param newRelicIngestionKey string

@description('Email of the account owner')
param userEmail string

// -----------------------------------------------------------------------------
// What to forward
// -----------------------------------------------------------------------------

param sendMetrics bool = true
param sendResourceLogs bool = true
param sendActivityLogs bool = true
param sendEntraLogs bool = false

@description('Resources with this tag are excluded from platform log forwarding')
param logExclusionTagName string = 'newrelicLogs'
param logExclusionTagValue string = 'exclude'

var tags = {
  environment: 'shared'
  owner: owner
  project: 'poc-observability'
  managedBy: 'bicep'
  purpose: 'newrelic-azure-native-integration'
}

resource rg 'Microsoft.Resources/resourceGroups@2024-03-01' = {
  name: resourceGroupName
  location: location
  tags: tags
}

module monitor './modules/newrelic-monitor.bicep' = {
  name: 'newrelic-monitor'
  scope: rg
  params: {
    location: location
    monitorName: monitorName
    tags: tags
    newRelicAccountId: newRelicAccountId
    newRelicOrganizationId: newRelicOrganizationId
    newRelicRegion: newRelicRegion
    newRelicIngestionKey: newRelicIngestionKey
    userEmail: userEmail
    sendMetrics: sendMetrics
    sendResourceLogs: sendResourceLogs
    sendActivityLogs: sendActivityLogs
    sendEntraLogs: sendEntraLogs
    logExclusionTagName: logExclusionTagName
    logExclusionTagValue: logExclusionTagValue
  }
}

output resourceGroupName string = rg.name
output monitorName string = monitor.outputs.monitorName
output monitorId string = monitor.outputs.monitorId
