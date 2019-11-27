package org.olf.export

import groovy.transform.AutoClone
import java.lang.reflect.Field
import groovy.util.logging.Slf4j
import com.opencsv.bean.CsvBindByPosition

import org.olf.erm.Entitlement
import org.olf.kb.ErmResource
import org.olf.kb.PackageContentItem
import org.olf.kb.Pkg
import org.olf.kb.TitleInstance
import org.olf.kb.PlatformTitleInstance
import org.olf.kb.IdentifierOccurrence
import org.olf.kb.Identifier
import org.olf.kb.IdentifierNamespace
import org.olf.kb.CoverageStatement
import org.olf.kb.Platform

import org.grails.web.util.WebUtils

@Slf4j
@AutoClone(excludes = ['date_first_issue_online', 'date_last_issue_online'])
public class KBart implements Serializable {

  KBart() {
    //
  }



  @CsvBindByPosition(position = 0)
  public String title_id = "" // not implemented
  @CsvBindByPosition(position = 1)
  public String publication_title = "" // TitleInstance.name
  @CsvBindByPosition(position = 2)
  public String title_url = "" // platformTitleInstance.url
  @CsvBindByPosition(position = 3)
  public String print_identifier = "" // titleInstance.identifiers.value WHERE .ns = "issn" OR ??? WHERE .ns = "isbn"
  @CsvBindByPosition(position = 4)
  public String online_identifier = "" // titleInstance.identifiers.value WHERE .ns = "eissn"
  @CsvBindByPosition(position = 5)
  public String date_first_issue_online = "" // derived from coverageService
  @CsvBindByPosition(position = 6)
  public String num_first_vol_online = "" // not implemented
  @CsvBindByPosition(position = 7)
  public String num_first_issue_online = "" // not implemented
  @CsvBindByPosition(position = 8)
  public String date_last_issue_online = "" // derived from coverageService
  @CsvBindByPosition(position = 9)
  public String num_last_vol_online = "" // not implemented
  @CsvBindByPosition(position = 10)
  public String num_last_issue_online = "" // not implemented
  @CsvBindByPosition(position = 11)
  public String first_author = "" // not implemented
  @CsvBindByPosition(position = 12)
  public String embargo_info = "" // not implemented
  @CsvBindByPosition(position = 13)
  public String coverage_depth = "" // platformContentItem.depth
  @CsvBindByPosition(position = 14)
  public String notes = "" // platformContentItem.note
  @CsvBindByPosition(position = 15)
  public String publisher_name = "" // ????
  @CsvBindByPosition(position = 16)
  public String publication_type  = "" // TitleInstance.type
  @CsvBindByPosition(position = 17)
  public String date_monograph_published_print = "" // not implemented
  @CsvBindByPosition(position = 18)
  public String date_monograph_published_online = "" // not implemented
  @CsvBindByPosition(position = 19)
  public String monograph_volume = "" // not implemented
  @CsvBindByPosition(position = 20)
  public String monograph_edition = "" // not implemented
  @CsvBindByPosition(position = 21)
  public String first_editor = "" // not implemented
  @CsvBindByPosition(position = 22)
  public String parent_publication_title_id = "" // not implemented
  @CsvBindByPosition(position = 23)
  public String preceding_publication_title_id = "" // not implemented
  @CsvBindByPosition(position = 24)
  public String access_type = "" // not implemented

  // public get methods are required for csv serialization

  public String getTitle_id() {
    return this.title_id
  }

  public String getPublication_title() {
    return this.publication_title
  }

  public String getTitle_url() {
    return this.title_url
  }

  public String getPrint_identifier() {
    return this.print_identifier
  }

  public String getOnline_identifier() {
    return this.online_identifier
  }

  public String getDate_first_issue_online() {
    return this.date_first_issue_online
  }

  public String getNum_first_vol_online() {
    return this.num_first_vol_online
  }

  public String getNum_first_issue_online() {
    return this.num_first_issue_online
  }


  public String getDate_last_issue_online() {
    return this.date_last_issue_online
  }

  public String getNum_last_vol_online() {
    return this.num_last_vol_online
  }

  public String getNum_last_issue_online() {
    return this.num_last_issue_online
  }

  public String getFirst_author() {
    return this.first_author
  }

  public String getEmbargo_info() {
    return this.embargo_info
  }

  public String getCoverage_depth() {
    return this.coverage_depth
  }

  public String getNotes() {
    return this.notes
  }

  public String getPublisher_name() {
    return this.publisher_name
  }

  public String getPublication_type() {
    return this.publication_type
  }


  public String getDate_monograph_published_print() {
    return this.date_monograph_published_print
  }

  public String getDate_monograph_published_online() {
    return this.date_monograph_published_online
  }

  public String getMonograph_volume() {
    return this.monograph_volume
  }

  public String getMonograph_edition() {
    return this.monograph_edition
  }

  public String getFirst_editor() {
    return this.first_editor
  }

  public String getParent_publication_title_id() {
    return this.parent_publication_title_id
  }

  public String getPreceding_publication_title_id() {
    return this.preceding_publication_title_id
  }

  public String getAccess_type() {
    return this.access_type
  }

  static String[] header() {
    List<String> header = new ArrayList<String>()
    KBart.class.getDeclaredFields().each {
      if (it.modifiers == java.lang.reflect.Modifier.PUBLIC) {
        header.add(it.name)
      }
    }
    return header as String[]
  }


  /*
   * Still a WIP.  Needs processing of obj[1] and obj[2]. For now, test data does not provide direct
   * Entitlements and no additional info for kbart export is available
   */
  static List<KBart> transform(final List<Object> objects) {

    def req = WebUtils.retrieveGrailsWebRequest().getCurrentRequest()

    List<KBart> kbartList = new ArrayList<KBart>();

    int resnum = 0;
    for (Object obj: objects) {
      resnum++
      KBart kbart = new KBart()
      ErmResource res  = (ErmResource) obj[0]
      //if (obj[1] != null) log.debug("obj[1] class: "+ obj[1].getClass().getName())
      //if (obj[2] != null) log.debug("obj[2] class: "+ obj[2].getClass().getName())

      if (obj[0] instanceof PackageContentItem) {
        PackageContentItem pci = (PackageContentItem) obj[0]

        PlatformTitleInstance pti = pci.pti
        TitleInstance ti = pti.titleInstance

        kbart.publication_title = ti.name
        kbart.publication_type = ti.type.value
        if (pci.depth) kbart.coverage_depth = pci.depth
        if (pci.note) kbart.notes = pci.note
        if (pti.url) kbart.title_url = pti.url

        Object identifiers_obj = ti.identifiers
        if (identifiers_obj) {
          Iterator iter = ti.identifiers.iterator();
          while (iter.hasNext()) {
            IdentifierOccurrence thisIdent = iter.next()
            Identifier ident =  thisIdent.identifier
            if (ident) {
              if (ident.ns.value.equals("eissn")) {
                kbart.online_identifier = ident.value
              } else if (ident.ns.value.equals("isbn")) {
                kbart.print_identifier = ident.value
              } else if (ident.ns.value.equals("issn")) {
                kbart.print_identifier = ident.value
              }
            }
          }
        }


        // get coverage statements. if there is more than one we need to clone the kbart object, one for
        // each coverage statement
        boolean hasCustomCoverage = false
        Map customCoverageMap = req?.getAttribute("export.kbart.customCoverage") as Map
        // log.debug("DEBUG: customCoverageMap size: "+ customCoverageMap.size())

        // Check for custom coverage on this resource.
        List<CoverageStatement> coverages = customCoverageMap?.get("${res.id}")
        if (coverages) {
          hasCustomCoverage = true
          log.debug("DEBUG: hasCustomCoverage = true")
        } else {
          coverages = pci.coverage
        }
        // log.debug("coverage size: "+ coverages.size())
        // add one kbart object for each coverage to list
        coverages.each { coverage ->
          //if (coverages.size() > 1 ) log.debug(resnum + ': title: '+ ti.name +' startDate: '+ coverage.startDate +" - endDate: "+ coverage.endDate )
          KBart kbartclone = new KBart();
          kbartclone = kbart.clone()
          kbartclone.date_first_issue_online = coverage.startDate.toString()
          kbartclone.date_last_issue_online = coverage.endDate.toString()

          kbartclone.num_first_issue_online = coverage.startIssue
          kbartclone.num_last_issue_online = coverage.endIssue

          kbartclone.num_first_vol_online = coverage.startVolume
          kbartclone.num_last_vol_online = coverage.endVolume
          kbartList.add(kbartclone)
        }
      }


    }
    return kbartList
  }
}

