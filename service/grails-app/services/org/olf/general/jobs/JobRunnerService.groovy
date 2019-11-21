package org.olf.general.jobs

import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

import javax.annotation.PostConstruct
import org.olf.ImportService
import org.olf.KbHarvestService
import org.slf4j.MDC
import com.k_int.okapi.OkapiTenantAdminService
import com.k_int.web.toolkit.refdata.Defaults
import com.k_int.web.toolkit.refdata.RefdataValue
import grails.events.EventPublisher
import grails.events.annotation.Subscriber
import grails.gorm.multitenancy.Tenants
import groovy.util.logging.Slf4j

@Slf4j
class JobRunnerService implements EventPublisher {
  
  // Any auto injected beans here can be accessed within the `work` runnable
  // of the job itself.
  
  OkapiTenantAdminService okapiTenantAdminService
  KbHarvestService kbHarvestService
  ImportService importService
  
  private static final class JobContext {
    Serializable jobId
    Serializable tenantId = Tenants.CurrentTenant.get()
  }
  public static final ThreadLocal<JobContext> jobContext = new ThreadLocal<JobContext>()
  
  int globalConcurrentJobs = 3 // We need to be careful to not completely tie up all our resource
  private ExecutorService executorSvc
  
  @PostConstruct
  void init() {
    // Set up the Executor
    
    // SO: This is not ideal. We don't want to limit jobs globally to 1 ideally. It should be 
    // 1 per tenant, but that will involve implementing custom handling for the queue and executor.
    // While we only have 1 tenant, this will suffice.
    executorSvc = new ThreadPoolExecutor(
      1, // Core pool Idle threads.
      1, // Treads max.
      1000, // Millisecond wait.
      TimeUnit.MILLISECONDS, // Makes the above wait time in 'seconds'
      new LinkedBlockingQueue<Runnable>() // Blocking queue
    )
//    executorSvc = Executors.newFixedThreadPool(1)
    
    // Get the list of jobs from all tenants that were interrupted by app termination and
    // set their states appropriately.
    
    // Raise an event to say we are ready.
    notify('jobs:job_runner_ready')
  }
  
  @Subscriber('jobs:job_created')
  void handleNewJob(final String jobId, final String tenantId) {
    // Attempt to append to queue.
    log.info "onJobCreated(${jobId}, ${tenantId})"
    enqueueJob(jobId, tenantId)
  }
  
  void setInterruptedJobsState() {
    log.debug "Checking for interrupted jobs"
    RefdataValue inProgress = PersistentJob.lookupStatus('In progress')
    PersistentJob.findAllByStatus(inProgress).each { PersistentJob j ->
      j.interrupted()
    }
  }
  
  void populateJobQueue() {
    log.debug "Populating intial job queue"
    final Map<Instant, List<String>> queue_order = new TreeMap<Instant, List<String>>()
        
    okapiTenantAdminService.getAllTenantSchemaIds().each { tenant_schema_id ->
      final String tid = tenant_schema_id as String
      Tenants.withId(tenant_schema_id) {
        setInterruptedJobsState()
        
        // Now load all queued jobs.
        RefdataValue queued = PersistentJob.lookupStatus('Queued')
        PersistentJob.findAllByStatus(queued).each { PersistentJob j ->
          queue_order.put(j.dateCreated, [j.id, tid])
        }
      }
    }
    
    // Enqueue each job.
    (queue_order.keySet() as List).reverse().each { Instant created ->
      def ids = queue_order[created]
      enqueueJob(ids[0], ids[1])
    }
  }
  
  public void enqueueJob(final String jobId, final String tenantId) {
    
    log.debug "Enqueueing job ${jobId} for ${tenantId}"
    // Use me within nested closures to ensure we are talking about this service.
    def me = this
    
    Tenants.withId(tenantId) {
      PersistentJob job = PersistentJob.read(jobId)
      Runnable work = job.getWork()
      if (Closure.isAssignableFrom(work.class)) {
        // Change the delegate to this class so we can control access to beans.
        Closure workC = work as Closure
        workC.setDelegate(me)
        workC.setResolveStrategy(Closure.DELEGATE_FIRST)
        
        // Also pass in the current tenant id.
        work = workC.curry(tenantId)
      }
      
      // We should wrap the work in a closure so we can ensure tenant id is set
      // as well as setting the job status on execution
      final Runnable currentWork = work
      work = { final String tid, final String jid, final Runnable wrk ->
          Tenants.withId(tid) {
            try {
              MDC.setContextMap( jobId: "${jid}", tenantId: "${tid}" )
              jobContext.set(new JobContext( jobId: jid, tenantId: tid ))
              beginJob(jid)
              wrk()
              endJob(jid)
            } catch (Exception e) {
              failJob(jid)
              log.error ("Job execution failed", e)
              notify ('jobs:log_info', jobContext.get().tenantId, jobContext.get().jobId,  "Job execution failed")
            } finally {
              jobContext.remove()
              log.debug "Finished task with jobId ${jid} and tenantId ${tid}"
              MDC.clear()
            }
          }
      }.curry(tenantId, jobId, currentWork)
      
      // Execute the work asynchronously.
      executorSvc.execute(work)
    }
  }
  
  public void beginJob(final String jid = null) {
    PersistentJob pj = PersistentJob.get(jid ?: jobContext.get().jobId)
    pj.begin()
  }

  public void endJob(final String jid = null) {
    PersistentJob pj = PersistentJob.get(jid ?: jobContext.get().jobId)
    pj.end()
  }

  public void failJob(final String jid = null) {
    PersistentJob pj = PersistentJob.get(jid ?: jobContext.get().jobId)
    pj.fail()
  }
}
