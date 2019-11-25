package org.olf.kb.adapters;

import java.text.*

import org.olf.dataimport.internal.InternalPackageImpl
import org.olf.dataimport.internal.PackageSchema
import org.olf.kb.KBCache
import org.olf.kb.KBCacheUpdater
import org.springframework.validation.BindingResult

import grails.web.databinding.DataBinder
import groovy.util.logging.Slf4j
import groovyx.net.http.*


/**
 * An adapter to go between the GOKb OAI service, for example the one at
 *   https://gokbt.gbv.de/gokb/oai/index/packages?verb=ListRecords&metadataPrefix=gokb
 * and our internal KBCache implementation.
 */

@Slf4j
public class GOKbOAIAdapter implements KBCacheUpdater, DataBinder {


  public void freshenPackageData(String source_name,
                                 String base_url,
                                 String current_cursor,
                                 KBCache cache) {

    log.debug("GOKbOAIAdapter::freshen - fetching from URI: ${base_url}");
    def jpf_api = new HTTPBuilder(base_url)

    def query_params = [
        'verb': 'ListRecords',
        'metadataPrefix': 'gokb'
    ]

    def cursor = null;
    def found_records = true;

    if ( current_cursor  != null ) {
      cursor = current_cursor
      query_params.from=cursor;
    }
    else {
      cursor = '';
    }

    while ( found_records ) {


      log.debug("** GET https://gokbt.gbv.de/gokb/oai/index/packages ${query_params}");

      jpf_api.request(Method.GET) { req ->
        // uri.path=''
        // requestContentType = ContentType.JSON
        headers.Accept = 'application/xml'
        uri.query=query_params

        response.success = { resp, xml ->
          // println "Success! ${resp.status} ${xml}"
          log.debug("got page of data from OAI, cursor=${cursor}, ...");

          Map page_result = processPage(cursor, xml, source_name, cache)


          log.debug("processPage returned, processed ${page_result.count} packages, cursor will be ${page_result.new_cursor}");
          // Store the cursor so we know where we are up to
          cache.updateCursor(source_name,page_result.new_cursor);

          if ( page_result.count > 0 ) {
            // If we processed records, and we have a resumption token, carry on.
            if ( page_result.resumptionToken ) {
              query_params.resumptionToken = page_result.resumptionToken
            }
            else {
              // Reached the end of the data
              found_records = false;
            }
          }
          else {
            found_records = false;
          }
        }

        response.failure = { resp ->
          log.debug "Request failed with status ${resp.status}"
          found_records = false;
        }
      }
    }

    log.debug("GOKbOAIAdapter::freshen - exiting URI: ${base_url}");
  }

  public void freshenHoldingsData(String cursor,
                                  String source_name,
                                  KBCache cache) {
    throw new RuntimeException("Holdings data not suported by GOKb");
  }


  private Map processPage(String cursor, Object oai_page, String source_name, KBCache cache) {

    final SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")

    // Force the formatter to use UCT because we want "Z" as the timezone
    sdf.setTimeZone(TimeZone.getTimeZone("UTC"));

    def result = [:]


    // If there is no cursor, initialise it to an empty string.
    result.new_cursor = (cursor && cursor.trim()) != '' ? cursor : '';
    result.count = 0;

    log.debug("GOKbOAIAdapter::processPage(${cursor},...");

    oai_page.ListRecords.record.each { record ->
      result.count++;
      def record_identifier = record?.header?.identifier?.text();
      def package_name = record?.metadata?.gokb?.package?.name?.text()
      def datestamp = record?.header?.datestamp?.text()

      log.debug("Processing OAI record :: ${result.count} ${record_identifier} ${package_name}");

      PackageSchema json_package_description = gokbToERM(record);
      if ( json_package_description.header.status == 'deleted' ) {
        // ToDo: Decide what to do about deleted records
      }
      else {
        cache.onPackageChange(source_name, json_package_description);
      }

      if ( datestamp > result.new_cursor ) {
        log.debug("Datestamp from record \"${datestamp}\" larger than current cursor (\"${result.new_cursor}\") - update it")
        // Because OAI uses >= we want to nudge up the cursor timestamp by 1s (2019-02-06T11:19:20Z)
        Date parsed_datestamp = parseDate(datestamp) // sdf.parse(datestamp);
        long incremented_datestamp = parsed_datestamp.getTime()+1000;
        String new_string_datestamp = sdf.format(new Date(incremented_datestamp));

        log.debug("New cursor value - \"${datestamp}\" > \"${result.new_cursor}\" - updating as \"${new_string_datestamp}\"");
        result.new_cursor = new_string_datestamp;
        log.debug("result.new_cursor=${result.new_cursor}");
      }
    }

    result.resumptionToken = oai_page.ListRecords?.resumptionToken?.text()
    return result;
  }

  /**
   * convert the gokb package metadataPrefix into our canonical ERM json structure as seen at
   *   https://github.com/folio-org/mod-erm/blob/master/service/src/integration-test/resources/packages/apa_1062.json
   * the GOKb records look like this
   *   https://gokbt.gbv.de/gokb/oai/index/packages?verb=ListRecords&metadataPrefix=gokb
   */
  private InternalPackageImpl gokbToERM(Object xml_gokb_record) {

    def package_record = xml_gokb_record?.metadata?.gokb?.package

    def result = null;

    if ( ( package_record != null ) && ( package_record.name != null ) ) {

      def package_name = package_record.name?.text()
      def package_shortcode = package_record.shortcode?.text()
      def nominal_provider = package_record.nominalProvider?.name?.text()


      result = [
        header:[
          status: xml_gokb_record?.header?.status?.text(),
          availability:[
            type: 'general'
          ],
          packageProvider:[
            name:nominal_provider
          ],
          packageSource:'GOKb',
          packageName: package_name,
          packageSlug: package_shortcode
        ],
        packageContents: []
      ]

      package_record.TIPPs?.TIPP.each { tipp_entry ->

        def tipp_status = tipp_entry?.status?.text()
        if ( tipp_status != 'Deleted' ) {
          def tipp_id = tipp_entry?.@id?.toString()
          def tipp_title = tipp_entry?.title?.name?.text()
          def tipp_medium = tipp_entry?.medium?.text()
          def tipp_media = null;

          // It appears that tipp_entry?.title?.type?.value() can be a list
          String title_type = tipp_entry?.title?.type?.text()

          switch ( title_type ) {
            case 'JournalInstance':
              tipp_media = 'journal'
              break;
            case 'BookInstance':
              tipp_media = 'book'
              break;
            default:
              tipp_media = 'journal'
              break;
          }
          def tipp_instance_identifiers = [] // [ "namespace": "issn", "value": "0278-7393" ]
          def tipp_sibling_identifiers = []

          // If we're processing an electronic record then issn is a sibling identifier
          tipp_entry.title.identifiers.identifier.each { ti_id ->
            if ( ti_id.@namespace == 'issn' ) {
              tipp_sibling_identifiers.add(["namespace": "issn", "value": ti_id.@value?.toString() ]);
            }
            else {
              tipp_instance_identifiers.add(["namespace": ti_id.@namespace?.toString(), "value": ti_id.@value?.toString() ]);
            }
          }

          def tipp_coverage = [] // [ "startVolume": "8", "startIssue": "1", "startDate": "1982-01-01", "endVolume": null, "endIssue": null, "endDate": null ],

          // Our domain model does not allow blank startDate or endDate, but they can be null
          String start_date_string = tipp_entry.coverage?.@startDate?.toString()
          String end_date_string = tipp_entry.coverage?.@endDate?.toString()

          tipp_coverage.add(["startVolume": tipp_entry.coverage?.@startVolume?.toString(),
                             "startIssue": tipp_entry.coverage?.@startIssue?.toString(),
                             "startDate": start_date_string?.length() > 0 ? start_date_string : null,
                             "endVolume":tipp_entry.coverage?.@endVolume?.toString(),
                             "endIssue": tipp_entry.coverage?.@endIssue?.toString(),
                             "endDate": end_date_string?.length() > 0 ? end_date_string : null])

          def tipp_coverage_depth = tipp_entry.coverage.@coverageDepth?.toString()
          def tipp_coverage_note = tipp_entry.coverage.@coverageNote?.toString()

          def tipp_url = tipp_entry.url?.text()
          def tipp_platform_url = tipp_entry.platform?.primaryUrl?.text()
          def tipp_platform_name = tipp_entry.platform?.name?.text()

          String access_start = tipp_entry.access?.@start?.toString()
          String access_end = tipp_entry.access?.@end?.toString()

          //Retired TIPPs are no longer in the package and should have an access_end, if not then make a guess at it
          if(access_end.length()==0 && tipp_status == "Retired") {
            access_end = tipp_entry.lastUpdated?.text().toString()
            log.info( "accessEnd date guessed for retired title: ${tipp_title} in package: ${package_name}. TIPP ID: ${tipp_id}" )
          }

          // log.debug("consider tipp ${tipp_title}");

          result.packageContents.add([
            "title": tipp_title,
            "instanceMedium": tipp_medium,
            "instanceMedia": tipp_media,
            "instanceIdentifiers": tipp_instance_identifiers,
            "siblingInstanceIdentifiers": tipp_sibling_identifiers,
            "coverage": tipp_coverage,
            "embargo": null,
            "coverageDepth": tipp_coverage_depth,
            "coverageNote": tipp_coverage_note,
            "platformUrl": tipp_platform_url,
            "platformName": tipp_platform_name,
            "url": tipp_url,
            "accessStart": access_start,
            "accessEnd": access_end
          ])
        }
      }
    }
    else {
      throw new RuntimeException("Problem decoding package record: ${package_record}");
    }

    InternalPackageImpl pkg = new InternalPackageImpl()
    BindingResult binding = bindData (pkg, result)
    if (binding?.hasErrors()) {
      binding.allErrors.each { log.debug "\t${it}" }
    }

    pkg
  }

  public Map importPackage(Map params,
                            KBCache cache) {
    throw new RuntimeException("Not yet implemented");
    return null;
  }

  public boolean activate(Map params, KBCache cache) {
    throw new RuntimeException("Not supported by this KB provider");
    return false;
  }

  public String makePackageReference(Map params) {
    throw new RuntimeException("Not yet implemented");
    return null;
  }

  // Move date parsing here - we might want to do something more sophistocated with different fallback formats
  // here in the future.
  Date parseDate(String s) {
    final SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX")
    return sdf.parse(s);
  }

}
