package org.olf

import org.olf.dataimport.internal.PackageSchema
import org.olf.dataimport.internal.PackageSchema.ContentItemSchema
import org.olf.dataimport.internal.PackageSchema.CoverageStatementSchema
import org.olf.general.jobs.JobRunnerService
import org.olf.general.jobs.LogEntry
import org.olf.kb.PackageContentItem
import org.olf.kb.Pkg
import org.olf.kb.Platform
import org.olf.kb.PlatformTitleInstance
import org.olf.kb.RemoteKB
import org.olf.kb.TitleInstance
import org.slf4j.MDC
import grails.gorm.transactions.Transactional
import grails.util.GrailsNameUtils
import groovy.util.logging.Slf4j
import java.util.concurrent.TimeUnit

/**
 * This service works at the module level, it's often called without a tenant context.
 */
@Slf4j
class PackageIngestService {

  // This boolean controls the behaviour of the loader when we encounter a title that does not have
  // a platform URL. We can error the row and do nothing, or create a row and point it at a proxy
  // platform to flag the error. Currently trialling the latter case. set to false to error and ignore the
  // row.
  private boolean PROXY_MISSING_PLATFORM = true

  TitleInstanceResolverService titleInstanceResolverService
  CoverageService coverageService

  // dependentModuleProxyService is a service which hides the fact that we might be dependent upon other
  // services for our reference data. In this class - vendors are erm Org entries, but in folio these are
  // managed by the vendors app. If we are running in folio mode, this service hides the detail of
  // looking up an Org in vendors and stashing the vendor info in the local cache table.
  DependentModuleProxyService dependentModuleProxyService

  public Map upsertPackage(PackageSchema package_data) {
    return upsertPackage(package_data,'LOCAL',true)
  }

  private static final def countChanges = ['accessStart', 'accessEnd']

  /**
   * Load the paackage data (Given in the agreed canonical json package format) into the KB.
   * This function must be passed VALID package data. At this point, all package contents are
   * assumed to be valid. Any invalid rows should be filtered out at this point.
   * This is to keep the implementation of this function clean, all error checking shoud be
   * performed prior to this step. This function is soley concerned with absorbing a valid
   * package into the KB.
   * @return id of package upserted
   */
  public Map upsertPackage(PackageSchema package_data, String remotekbname, boolean readOnly=false) {

    def result = [
      startTime: System.currentTimeMillis(),
      titleCount: 0,
      newTitles: 0,
      removedTitles: 0,
      updatedTitles: 0,
      updatedAccessStart: 0,
      updatedAccessEnd: 0,
    ]

    Pkg pkg = null

    Pkg.withNewTransaction { status ->
      // ERM caches many remote KB sources in it's local package inventory
      // Look up which remote kb via the name
      RemoteKB kb = RemoteKB.findByName(remotekbname)
      
      if (!kb) {
       kb = new RemoteKB( name:remotekbname,
                          rectype: new Long(1),
                          active: Boolean.TRUE,
                          readonly:readOnly).save(flush:true, failOnError:true)
      }

      result.updateTime = System.currentTimeMillis()

      log.info("Package header: ${package_data.header} - update start time is ${result.updateTime}")

      // header.packageSlug contains the package maintainers authoritative identifier for this package.
      pkg = Pkg.findBySourceAndReference(package_data.header.packageSource, package_data.header.packageSlug)

      def vendor = null
      if ( ( package_data.header?.packageProvider?.name != null ) && ( package_data.header?.packageProvider?.name.trim().length() > 0 ) ) {
        log.debug("Package contains provider information: ${package_data.header?.packageProvider?.name} -- trying to match to an existing organisation.")
        vendor = dependentModuleProxyService.coordinateOrg(package_data.header?.packageProvider?.name)
        // reference has been removed at the request of the UI team
        // vendor.enrich(['reference':package_data.header?.packageProvider?.reference])
      }
      else {
        log.warn('Package ingest - no provider information present')
      }

      if ( pkg == null ) {
        pkg = new Pkg(
              name: package_data.header.packageName,
             source: package_data.header.packageSource,
          reference: package_data.header.packageSlug,
           remoteKb: kb,
             vendor: vendor).save(flush:true, failOnError:true)
      }
      result.packageId = pkg.id
    }

    package_data.packageContents.eachWithIndex { ContentItemSchema pc, int index ->
      MDC.put('subDiscriminator', "Content item #${index + 1}")

      // log.debug("Try to resolve ${pc}")

      try {

        PackageContentItem.withNewTransaction { status ->

          // resolve may return null, used to throw exception which causes the whole package to be rejected. Needs
          // discussion to work out best way to handle.
          TitleInstance title = titleInstanceResolverService.resolve(pc)

          if ( title != null ) {

            // log.debug("platform ${pc.platformUrl} ${pc.platformName} (item URL is ${pc.url})")

            // lets try and work out the platform for the item
            def platform_url_to_use = pc.platformUrl

            if ( ( pc.platformUrl == null ) && ( pc.url != null ) ) {
              // No platform URL, but a URL for the title. Parse the URL and generate a platform URL
              def parsed_url = new java.net.URL(pc.url)
              platform_url_to_use = "${parsed_url.getProtocol()}://${parsed_url.getHost()}"
            }

            Platform platform = Platform.resolve(platform_url_to_use, pc.platformName)
            // log.debug("Platform: ${platform}")

            if ( platform == null && PROXY_MISSING_PLATFORM ) {
              platform = Platform.resolve('http://localhost.localdomain', 'This platform entry is used for error cases')
            }

            if ( platform != null ) {

              // See if we already have a title platform record for the presence of this title on this platform
              PlatformTitleInstance pti = PlatformTitleInstance.findByTitleInstanceAndPlatform(title, platform)

              if ( pti == null )
                pti = new PlatformTitleInstance(titleInstance:title,
                  platform:platform,
                  url:pc.url).save(flush:true, failOnError:true)


              // Lookup or create a package content item record for this title on this platform in this package
              // We only check for currently live pci records, as titles can come and go from the package.
              // N.B. addedTimestamp removedTimestamp lastSeenTimestamp
              def pci_qr = PackageContentItem.executeQuery('select pci from PackageContentItem as pci where pci.pti = :pti and pci.pkg.id = :pkg and pci.removedTimestamp is null',
                  [pti:pti, pkg:result.packageId])
              PackageContentItem pci = pci_qr.size() == 1 ? pci_qr.get(0) : null;

              boolean isUpdate = false
              boolean isNew = false
              if ( pci == null ) {
                log.debug("Record ${result.titleCount} - Create new package content item")
                pci = new PackageContentItem(
                  pti:pti,
                  pkg:Pkg.get(result.packageId),
                  addedTimestamp:result.updateTime)
                isNew = true
              }
              else {
                // Note that we have seen the package content item now - so we don't delete it at the end.
                log.debug("Record ${result.titleCount} - Update package content item (${pci.id})")
                isUpdate = true
              }

              // Add/Update common properties.
              pci.with {
                note = pc.coverageNote
                depth = pc.coverageDepth
                accessStart = pc.accessStart
                accessEnd = pc.accessEnd
                addedTimestamp = result.updateTime
                lastSeenTimestamp = result.updateTime
              }

              // ensure that accessStart is earlier than accessEnd, otherwise stop processing the current item
              if (pci.accessStart != null && pci.accessEnd != null) {
                if (pci.accessStart > pci.accessEnd ) {
                  log.error("accessStart date cannot be after accessEnd date for title: ${title} in package: ${pkg.name}")
                  return
                }
              }

              if (isUpdate) {
                if (pci.isDirty()) {
                  // This means we have changes to an existing PCI and not a new one.
                  result.updatedTitles++

                  // Grab the dirty properties
                  def modifiedFieldNames = pci.getDirtyPropertyNames()
                  for (fieldName in modifiedFieldNames) {
                    if (fieldName == "accessStart") {
                      result.updatedAccessStart++
                    }
                    if (fieldName == "accessEnd") {
                      result.updatedAccessEnd++
                    }
                    if (countChanges.contains(fieldName)) {
                      def currentValue = pci."$fieldName"
                      def originalValue = pci.getPersistentValue(fieldName)
                      if (currentValue != originalValue) {
                        result["${fieldName}"] = (result["${fieldName}"] ?: 0)++
                      }
                    }
                  }
                }
              } else if (isNew) {
                // New item.
                result.newTitles++
              }

              pci.save(flush: true, failOnError: true)

              // If the row has a coverage statement, check that the range of coverage we know about for this title on this platform
              // extends to include the supplied information. It is a contract with the KB that we assume this is correct info.
              // We store this generally for the title on the platform, and specifically for this title in this package on this platform.
              if ( pc.coverage ) {

                // We define coverage to be a list in the exchange format, but sometimes it comes just as a JSON map. Convert that
                // to the list of maps that coverageService.extend expects
                Iterable<CoverageStatementSchema> cov = pc.coverage instanceof Iterable ? pc.coverage : [ pc.coverage ]

                coverageService.extend(pti, cov)
                coverageService.extend(pci, cov)
                coverageService.extend(title, cov)
              }

              // Save needed either way
              pci.save(flush:true, failOnError:true)
            }
            else {
              String message = "Skipping ${pc.title}/${pc}. Unable to identify platform from ${platform_url_to_use} and ${pc.platformName}"
              log.error(message)
            }
          }
          else {
            String message = "Skipping ${pc.title}/${pc}. Unable to resolve title from ${pc.title} with identifiers ${pc.instanceIdentifiers}"
            log.error(message)
          }
        }
      } catch ( Exception e ) {
        String message = "Skipping ${pc.title}/${pc}, System error: ${e.message}"
        log.error(message,e)
      }

      // {
      //   "title": "Nordic Psychology",
      //   "instanceMedium": "electronic",
      //   "instanceMedia": "journal",
      //   "instanceIdentifiers": {
      //   "namespace": "jusp",
      //   "value": "12342"
      //   },
      //   "siblingInstanceIdentifiers": {
      //   "namespace": "issn",
      //   "value": "1901-2276"
      //   },
      //   "coverage": {
      //   "startVolume": "58",
      //   "startIssue": "1",
      //   "startDate": "2006-03-31T23:00:00Z",
      //   "endVolume": "63",
      //   "endIssue": "4",
      //   "endDate": "2011-12-31T00:00:00Z"
      //   },
      //   "embargo": null,
      //   "coverageDepth": "fulltext",
      //   "coverageNote": null
      //   }
      result.titleCount++
      result.averageTimePerTitle=(System.currentTimeMillis()-result.startTime)/result.titleCount
      if ( result.titleCount % 100 == 0 ) {
        log.debug ("Processed ${result.titleCount} titles, average per title: ${result.averageTimePerTitle}")
      }
    }
    def finishedTime = (System.currentTimeMillis()-result.startTime)/1000

    // At the end - Any PCIs that are currently live (Don't have a removedTimestamp) but whos lastSeenTimestamp is < result.updateTime
    // were not found on this run, and have been removed. We *may* introduce some extra checks here - like 3 times or a time delay, but for now,
    // this is how we detect deletions in the package file.
    log.debug("Remove any content items that have disappeared since the last upload. ${pkg.name}/${pkg.source}/${pkg.reference}/${result.updateTime}")
    int removal_counter = 0

    PackageContentItem.withNewTransaction { status ->

      PackageContentItem.executeQuery('select pci from PackageContentItem as pci where pci.pkg = :pkg and pci.lastSeenTimestamp < :updateTime and pci.removedTimestamp is null',
                                      [pkg:pkg, updateTime:result.updateTime]).each { removal_candidate ->
        try {
          log.debug("Removal candidate: pci.id #${removal_candidate.id} (Last seen ${removal_candidate.lastSeenTimestamp}, thisUpdate ${result.updateTime}) -- Set removed")
          removal_candidate.removedTimestamp = result.updateTime
          removal_candidate.save(flush:true, failOnError:true)
        } catch ( Exception e ) {
          log.error("Problem removing ${removal_candidate} in package load",e)
        }
        result.removedTitles++
      }
    }

    // Need to pause long enough so that the timestamps are different
    TimeUnit.MILLISECONDS.sleep(1)
    if (result.titleCount > 0) {
      log.info ("Processed ${result.titleCount} titles in ${finishedTime} seconds (${finishedTime/result.titleCount} average)")
      TimeUnit.MILLISECONDS.sleep(1)
      log.info ("Added ${result.newTitles} titles")
      TimeUnit.MILLISECONDS.sleep(1)
      log.info ("Updated ${result.updatedTitles} titles")
      TimeUnit.MILLISECONDS.sleep(1)
      log.info ("Removed ${result.removedTitles} titles")
      log.info ("Updated accessStart on ${result.updatedAccessStart} title(s)")
      log.info ("Updated accessEnd on ${result.updatedAccessEnd} title(s)")

      // Log the counts too.
      for (final String change : countChanges) {
        if (result[change]) {
          TimeUnit.MILLISECONDS.sleep(1)
          log.info ("Changed ${GrailsNameUtils.getNaturalName(change).toLowerCase()} on ${result[change]} titles")
        }
      }
    } else {
      if (result.titleCount > 0) {
        log.info ("No titles to process")
      }
    }

    return result
  }
}
