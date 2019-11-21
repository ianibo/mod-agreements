package org.olf;

import grails.gorm.multitenancy.Tenants;
import grails.gorm.transactions.Transactional
import org.olf.kb.RemoteKB;
import org.olf.kb.KBCacheUpdater;
import org.olf.kb.PlatformTitleInstance
import org.olf.kb.ContentActivationRecord
import org.olf.dataimport.internal.PackageSchema
import org.olf.erm.Entitlement
import org.springframework.transaction.TransactionDefinition

/**
 * This service works at the module level, it's often called without a tenant context.
 */
@Transactional
public class KnowledgeBaseCacheService implements org.olf.kb.KBCache {

  PackageIngestService packageIngestService

  private static final String PLATFORM_TITLES_QUERY = '''select pti, rkb, ent from PlatformTitleInstance as pti, Entitlement as ent, RemoteKB as rkb 
where ( exists ( select pci.id 
               from PackageContentItem as pci
               where pci.pti = pti
               and ent.resource = pci.pkg )
   or exists ( select pci.id 
               from PackageContentItem as pci
               where pci.pti = pti
               and ent.resource = pci )
   or ent.resource = pti )
  and rkb.activationSupported = true 
  and rkb.activationEnabled = true
  and not exists ( select car from ContentActivationRecord as car where car.pti = pti and car.target = rkb )
'''


  public void triggerCacheUpdate() {
    log.debug("KnowledgeBaseCacheService::triggerCacheUpdate()");

  }

  public void runSync(String remotekb_id) {
    log.debug("KnowledgeBaseCacheService::runSync(${remotekb_id})");
    // Even though we just need a read-only connection, we still need to wrap this block
    // with withNewTransaction because of https://hibernate.atlassian.net/browse/HHH-7421
    RemoteKB.withNewTransaction {
      RemoteKB rkb = RemoteKB.read(remotekb_id) 
      if ( rkb ) {
        log.debug("Run remote kb sync:: ${rkb.id}/${rkb.name}/${rkb.uri}");
        Class cls = Class.forName(rkb.type)
        KBCacheUpdater cache_updater = cls.newInstance();
        cache_updater.freshenPackageData(rkb.name, rkb.uri, rkb.cursor, this)
      }
    }
  }

  public void updateCursor(String rkb_name, String cursor) {
    log.debug("KnowledgeBaseCacheService::updateCursor(${rkb_name},${cursor})");
    RemoteKB.withTransaction([propagationBehavior: TransactionDefinition.PROPAGATION_REQUIRES_NEW]) {
      RemoteKB.executeUpdate('update RemoteKB rkb set rkb.cursor = :n where rkb.name = :name',[n:cursor, name:rkb_name]);
    }
  }



  /**
   *  Called when a remote KB package add/update is detected. It is the responsibility
   *  of the KBCacheUpdater to convert the remote package format into the canonical object map  
   *  defined here
   *  This document: https://docs.google.com/document/d/14KIi4Guhu8r1NM7lr8NH6SI7giyAdmFjjy4Q6x-MMvQ/edit
   *  Drive -> ERM-Project Team -> WorkInProgress -> olf-erm analysis and design -> PackageDescription--JSON format
   *  Examples can be found in src/intergation-test/resources/packages. The function should be called with the
   *  result of parsing that JSON into a map, eg via JsonSlurper
   *
   *  @param rkb_name the ID string of the remote KB as defined in the remoteKB domain
   *  @param package_data the parsed JSON upload format - for example see the package at
   *              https://github.com/folio-org/mod-erm/blob/master/service/src/integration-test/resources/packages/apa_1062.json
   *
   *  @return map containing information about the packageId of the newly loaded or existing updated package
   */
  public Map onPackageChange(String rkb_name, PackageSchema package_data) {
    Map result = null;
    RemoteKB.withTransaction([propagationBehavior: TransactionDefinition.PROPAGATION_REQUIRES_NEW]) {
      log.debug("onPackageChange(${rkb_name},...)");
      result = packageIngestService.upsertPackage(package_data, rkb_name);
    }
    log.debug("onPackageChange(${rkb_name},...) returning ${result}");

    return result;
  }

  /**
   *  Called when a remote KB package removal is detected
   */
  public void onPackageRemoved(String rkb_name,
                               String authority,
                               String authority_id_of_package) {
    log.debug("onPackageRemoved(${rkb_name},${authority}, ${authority_id_of_package})");
  }


  /**
   * Trigger the activation update procedure.
   */
  public void triggerActivationUpdate() {

    Map<String, KBCacheUpdater> adapter_cache = [:]

    log.debug("KnowledgeBaseCacheService::triggerActivationUpdate()");
    int activation_count = 0;
    RemoteKB.executeQuery(PLATFORM_TITLES_QUERY).each { qr ->
      log.debug("Content Activation: ${qr}");
      PlatformTitleInstance pti = qr[0]
      RemoteKB rkb = qr[1]
      Entitlement ent = qr[2]

      def adapter = getAdapter(adapter_cache, rkb);
      
      if ( adapter.activate([pti:pti, ent:ent], this) ) {
        log.debug("Activation OK - create CAR");
        def car = new ContentActivationRecord(dateActivation:new Date(),
                                              dateDeactivation:null,
                                              target: rkb,
                                              pti: pti).save(flush:true, failOnError:true);
      }
      else {
        log.debug("Activation Failed - no CAR");
      }
      activation_count++;
    }

    log.debug("triggerActivationUpdate() - ${activation_count} activations");

    return
  }

  private KBCacheUpdater getAdapter(Map m, RemoteKB rkb) {
    KBCacheUpdater result = m[rkb.type]
    if ( result == null ) {
      Class cls = Class.forName(rkb.type)
      result = cls.newInstance();
      m[rkb.type] = result;
    }
    return result;
  }
}
