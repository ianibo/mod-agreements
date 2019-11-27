package org.olf.general.jobs

import grails.gorm.MultiTenant
import groovy.json.JsonSlurper

class PackageImportJob extends PersistentJob implements MultiTenant<PackageImportJob> {
  
  private final JsonSlurper js = new JsonSlurper()
  
  final Closure getWork() {
    
    final Closure theWork = { final String eventId, final String tenantId ->
    
      log.info "Running Package Import Job"
      PersistentJob.withTransaction {
      
        // We should ensure the job is read into the current session. This closure will probably execute
        // in a future session and we need to reread the event in.
        final PersistentJob job = PersistentJob.read(eventId)
        if (job.fileUpload) {
          importService.importFromFile(js.parse( job.fileUpload.fileObject.fileContents.binaryStream ))
        } else {
          log.error "No file attached to the Job."
        }
      }
    }.curry( this.id )
    
    theWork
  }
  
  void beforeValidate() {
    if (!this.name && this.fileUpload) {
      // Set the name from the file upload if no name has been set.
      this.name = "Import package from ${this.fileUpload.fileName}"
    }
  }
  
  static constraints = {
      fileUpload (nullable:false)
  }
}
