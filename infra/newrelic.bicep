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

@description('Region of the resource group that holds the monitor. Only metadata: it does not have to match the region of the monitor itself')
param location string = 'westeurope'

// Region of the monitor resource, deliberately independent from the region of
// the PoC. The type NewRelic.Observability/monitors is NOT available in every
// region: deploying it in westeurope fails with
// LocationNotAvailableForResourceType. It does not matter for coverage, because
// the tag rules apply to the WHOLE subscription regardless of where the monitor
// lives.
//
// The deploy workflow resolves a valid region on its own. To check the list by
// hand (the JMESPath needs quotes, which is why this is a comment and not part
// of the @description string):
//
//   az provider show --namespace NewRelic.Observability \
//     --query "resourceTypes[?resourceType=='monitors'].locations" -o json
@description('Region of the New Relic monitor resource. Not every region offers this resource type, see the comment above')
param monitorLocation string = 'eastus'

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

// Los nombres de despliegue de los modulos llevan un sufijo unico. Con un
// nombre fijo, un despliegue de modulo que queda bloqueado impide los
// siguientes durante 7 dias con:
//   DeploymentActive: ... cannot be saved, because this would overwrite an
//   existing deployment which is still active ... will expire at <+7 dias>
// uniqueString(deployment().name) deriva del nombre del despliegue externo, que
// la pipeline ya hace unico por ejecucion.
module monitor './modules/newrelic-monitor.bicep' = {
  name: 'newrelic-monitor-${uniqueString(deployment().name)}'
  scope: rg
  params: {
    // Deliberadamente monitorLocation y no location: el tipo de recurso solo
    // existe en algunas regiones, y el resource group puede estar en otra.
    location: monitorLocation
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
output monitorLocation string = monitorLocation
