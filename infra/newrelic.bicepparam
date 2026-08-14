// =============================================================================
// newrelic.bicepparam - one time setup of the native integration
// -----------------------------------------------------------------------------
// Usage (the "using" statement points to the template, so --template-file must
// not be passed):
//   az deployment sub create --location westeurope --parameters infra/newrelic.bicepparam
// =============================================================================

using './newrelic.bicep'

param location = readEnvironmentVariable('AZURE_LOCATION', 'westeurope')
param resourceGroupName = readEnvironmentVariable('NR_RESOURCE_GROUP', 'rg-newrelic-shared')
param monitorName = readEnvironmentVariable('NR_MONITOR_NAME', 'newrelic-poc-observability')
param owner = readEnvironmentVariable('POC_OWNER', 'unknown')

param newRelicAccountId = readEnvironmentVariable('NR_ACCOUNT_ID', '')
param newRelicOrganizationId = readEnvironmentVariable('NR_ORGANIZATION_ID', '')
param newRelicRegion = readEnvironmentVariable('NR_REGION', 'eu')
param newRelicIngestionKey = readEnvironmentVariable('NR_LICENSE_KEY', '')
param userEmail = readEnvironmentVariable('NR_USER_EMAIL', '')

param sendMetrics = bool(readEnvironmentVariable('NR_SEND_METRICS', 'true'))
param sendResourceLogs = bool(readEnvironmentVariable('NR_SEND_RESOURCE_LOGS', 'true'))
param sendActivityLogs = bool(readEnvironmentVariable('NR_SEND_ACTIVITY_LOGS', 'true'))
param sendEntraLogs = bool(readEnvironmentVariable('NR_SEND_ENTRA_LOGS', 'false'))

param logExclusionTagName = readEnvironmentVariable('NR_LOG_EXCLUSION_TAG_NAME', 'newrelicLogs')
param logExclusionTagValue = readEnvironmentVariable('NR_LOG_EXCLUSION_TAG_VALUE', 'exclude')
