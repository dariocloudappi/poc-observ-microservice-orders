// =============================================================================
// modules/newrelic-monitor.bicep - Azure Native New Relic Service
// -----------------------------------------------------------------------------
// Creates the New Relic monitor resource and its tag rules. With this resource
// in place, Azure itself creates and removes the diagnostic settings of the
// matching resources, pointing at New Relic. There is no Event Hub, no Storage
// Account and no forwarding Function, so the forwarding path costs nothing in
// Azure.
//
// The account is LINKED, not created: accountCreationSource and
// orgCreationSource are set to NEWRELIC, which means billing stays with the
// existing New Relic organization and no Azure Marketplace SaaS resource is
// created.
//
// Deduplication is the reason for the filtering tags. The application already
// ships its own logs to New Relic through the OpenTelemetry agent, so the web
// app is excluded from the platform log forwarding. Its platform metrics are
// still collected, because those the agent cannot see.
// =============================================================================

param location string
param monitorName string
param tags object

@description('New Relic account id, from one.newrelic.com')
param newRelicAccountId string

@description('New Relic organization id')
param newRelicOrganizationId string

@description('Region of the New Relic account: us or eu')
@allowed([
  'us'
  'eu'
])
param newRelicRegion string

@description('Ingest license key of the New Relic account')
@secure()
param newRelicIngestionKey string

@description('Email of the account owner. The resource provider requires it')
param userEmail string

param userFirstName string = 'PoC'
param userLastName string = 'Observability'

@description('Send platform metrics of the subscription to New Relic')
param sendMetrics bool = true

@description('Send Azure resource logs to New Relic')
param sendResourceLogs bool = true

@description('Send the subscription Activity Log to New Relic')
param sendActivityLogs bool = true

@description('Send Microsoft Entra logs. Off by default: high volume and out of scope for the PoC')
param sendEntraLogs bool = false

@description('Resources carrying this tag are excluded from platform log forwarding, to avoid duplicating what the OpenTelemetry agent already sends')
param logExclusionTagName string = 'newrelicLogs'

param logExclusionTagValue string = 'exclude'

var enabledFlag = {
  'true': 'Enabled'
  'false': 'Disabled'
}

resource monitor 'NewRelic.Observability/monitors@2024-10-01' = {
  name: monitorName
  location: location
  tags: tags
  identity: {
    // Azure assigns the Monitoring Reader role to this identity by itself.
    // Removing it stops the metric collection.
    type: 'SystemAssigned'
  }
  properties: {
    // NEWRELIC on both means "link the organization and account that already
    // exist", instead of creating a new one through Marketplace.
    accountCreationSource: 'NEWRELIC'
    orgCreationSource: 'NEWRELIC'
    newRelicAccountProperties: {
      accountInfo: {
        accountId: newRelicAccountId
        ingestionKey: newRelicIngestionKey
        region: newRelicRegion
      }
      organizationInfo: {
        organizationId: newRelicOrganizationId
      }
    }
    userInfo: {
      emailAddress: userEmail
      firstName: userFirstName
      lastName: userLastName
      country: 'ES'
    }
  }
}

resource tagRules 'NewRelic.Observability/monitors/tagRules@2024-10-01' = {
  parent: monitor
  name: 'default'
  properties: {
    metricRules: {
      sendMetrics: enabledFlag['${sendMetrics}']
      userEmail: userEmail
      // No filter: platform metrics are wanted from every resource, including
      // the web app, because the agent cannot see them.
      filteringTags: []
    }
    logRules: {
      sendActivityLogs: enabledFlag['${sendActivityLogs}']
      sendSubscriptionLogs: enabledFlag['${sendResourceLogs}']
      sendAadLogs: enabledFlag['${sendEntraLogs}']
      // Exclusion wins over inclusion. Everything is forwarded except the
      // resources tagged as already covered by the OpenTelemetry agent.
      filteringTags: [
        {
          name: logExclusionTagName
          value: logExclusionTagValue
          action: 'Exclude'
        }
      ]
    }
  }
}

output monitorId string = monitor.id
output monitorName string = monitor.name
output principalId string = monitor.identity.principalId
