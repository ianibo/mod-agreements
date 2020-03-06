package org.olf

import static groovy.transform.TypeCheckingMode.SKIP

import java.time.Instant

import org.olf.general.jobs.PackageIngestJob
import org.olf.kb.RemoteKB
import org.springframework.scheduling.annotation.Scheduled

import com.k_int.okapi.OkapiTenantAdminService
import com.k_int.okapi.OkapiTenantResolver

import grails.events.annotation.Subscriber
import grails.gorm.multitenancy.Tenants
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * See http://guides.grails.org/grails-scheduled/guide/index.html for info on this way of
 * scheduling tasks
 */
@Slf4j
@CompileStatic
class KbHarvestService {

  // Without this, the service will be lazy initialised, and the tasks won't be scheduled until an external
  // tries to access the instance.
  boolean lazyInit = false

  OkapiTenantAdminService okapiTenantAdminService
  KnowledgeBaseCacheService knowledgeBaseCacheService

  // All remote KBs not currently syncing and which have not been synced in the last 1 hour
  private static final PENDING_JOBS_HQL = '''select rkb.id
from RemoteKB as rkb
where rkb.type is not null
  and rkb.active = :true
  and ( ( rkb.syncStatus is null ) OR ( rkb.syncStatus <> :inprocess ) )
  and ( ( rkb.lastCheck is null ) OR ( ( :current_time - rkb.lastCheck ) < 1*60*60*1000 ) )
'''

  @Subscriber('okapi:dataload:sample')
  public void onDataloadSample (final String tenantId, final String value, final String existing_tenant, final String upgrading, final String toVersion, final String fromVersion) {
    log.debug "Perform trigger sync for new tenant ${tenantId} via data load event"
    final String schemaName = OkapiTenantResolver.getTenantSchemaName(tenantId)
    triggerUpdateForTenant(schemaName)
  }

  @CompileStatic(SKIP)
  private void triggerUpdateForTenant(final String tenant_schema_id) {
    Tenants.withId(tenant_schema_id) {

      PackageIngestJob job = PackageIngestJob.findByStatusInList([
        PackageIngestJob.lookupStatus('Queued'),
        PackageIngestJob.lookupStatus('In progress')
      ])

      if (!job) {
        job = new PackageIngestJob(name: "Scheduled Ingest Job ${Instant.now()}")
        job.setStatusFromString('Queued')
        job.save(failOnError: true, flush: true)
      } else {
        log.debug('Harvester already running or scheduled. Ignore.')
      }
    }
  }

  @Scheduled(fixedDelay = 3600000L, initialDelay = 60000L) // Run task every hour, wait 1 minute.
  void triggerSync() {
    log.debug "Running scheduled KB sync for all tenants :{}", Instant.now()

    // ToDo: Don't think this will work for newly added tenants - need to investigate.
    okapiTenantAdminService.getAllTenantSchemaIds().each { tenant_schema_id ->
      log.debug "Perform trigger sync for tenant schema ${tenant_schema_id}"
      triggerUpdateForTenant(tenant_schema_id as String)
    }
  }

  @CompileStatic(SKIP)
  public void triggerCacheUpdate() {
    log.debug("KBHarvestService::triggerCacheUpdate()")

    // List all pending jobs that are eligible for processing - That is everything enabled and not currently in-process and has not been processed in the last hour
    RemoteKB.executeQuery(PENDING_JOBS_HQL,['true':true,'inprocess':'in-process','current_time':System.currentTimeMillis()],[lock:false]).each { remotekb_id ->

      try {
        // We will check each candidate job to see if it has been picked up by some other thread or load balanced
        // instance of mod-agreements. We assume it has
        boolean continue_processing = false

        // Lock the actual RemoteKB record so that nobody else can grab it for processing
        RemoteKB.withNewSession {

          // Get hold of the actual job, lock it, and if it's still not in process, set it's status to in-process
          RemoteKB rkb = RemoteKB.lock(remotekb_id)

          // Now that we hold the lock, we can checm again to see if it's in-process
          if ( rkb.syncStatus != 'in-process' ) {
            // Set it to in-process, and continue
            rkb.syncStatus = 'in-process'
            continue_processing = true
          }

          // Save and close the transaction, removing the lock
          rkb.save(flush:true, failOnError:true)
        }

        // If we managed to grab a remote kb and update it to in-process, we had better process it
        if ( continue_processing ) {
          log.debug("Run sync on ${remotekb_id}")
          try {
            // Even though we just need a read-only connection, we still need to wrap this block
            // with withNewTransaction because of https://hibernate.atlassian.net/browse/HHH-7421
            knowledgeBaseCacheService.runSync((String)remotekb_id)
          }
          catch ( Exception e ) {
            log.warn("problem processing remote KB link",e)
          }
          finally {
            // Finally, set the state to idle
            RemoteKB.withNewSession {
              RemoteKB rkb = RemoteKB.lock(remotekb_id)
  
              rkb.syncStatus = 'idle'
              rkb.lastCheck = System.currentTimeMillis()
              rkb.save(flush:true, failOnError:true)
            }
          }
        }
        else {
          log.info("Skipping remote kb ${remotekb_id} as sync status is ${rkb.syncStatus}");
        }
      }
      catch ( Exception e ) {
        log.error("Unexpected problem in RemoteKB Update",e);
      }
    }

    log.debug("KbHarvestService::triggerCacheUpdate() completed")
  }
}
