package org.olf

import org.olf.dataimport.internal.PackageContentImpl
import org.olf.dataimport.internal.PackageSchema.ContentItemSchema
import org.olf.dataimport.internal.PackageSchema.IdentifierSchema
import org.olf.kb.Identifier
import org.olf.kb.IdentifierNamespace
import org.olf.kb.IdentifierOccurrence
import org.olf.kb.TitleInstance
import org.olf.kb.Work

import grails.gorm.transactions.Transactional
import grails.web.databinding.DataBinder

/**
 * This service works at the module level, it's often called without a tenant context.
 */
class TitleInstanceResolverService implements DataBinder{

  private static final float MATCH_THRESHOLD = 0.775f
  private static final String TEXT_MATCH_TITLE_HQL = '''
   SELECT ti from TitleInstance as ti
    WHERE 
      trgm_match(ti.name, :qrytitle) = true
      AND similarity(ti.name, :qrytitle) > :threshold
      AND ti.subType.value like :subtype
    ORDER BY similarity(ti.name, :qrytitle) desc
  '''

  private static final String SIBLING_MATCH_HQL = '''
    from TitleInstance as ti
      WHERE 
        exists ( SELECT sibling FROM TitleInstance as sibling 
                   JOIN sibling.identifiers as io
                 WHERE 
                   sibling.work = ti.work
                     AND
                   io.identifier.ns.value = :ns
                     AND
                   io.identifier.value = :value
        )
          AND
        ti.subType.value = :electronic
  '''

  private static def class_one_namespaces = [
    'zdb',
    'isbn',
    'issn',  // This really isn't true - we get electronic items identified by the issn of their print sibling.. Needs thought
    'eissn',
    'doi'
  ];
  
  private static final def APPROVED = 'approved'

  /**
   * Given a -valid- title citation with the minimum properties below, attempt to resolve the citation
   * into a local instance record. If no instance record is located, create one, and perform the necessary
   * cross-matching to create Inventory Instance records. The map contains a representation that is
   * the same as the attached JSON.
   *
   * {
   *   "title": "Nordic Psychology",
   *   "instanceMedium": "electronic",
   *   "instanceMedia": "journal",
   *   "instanceIdentifiers": [ 
   *     {
   *       "namespace": "issn",
   *       "value": "1234-5678"
   *     } ],
   *   "siblingInstanceIdentifiers": [ 
   *     {
   *       "namespace": "issn",
   *       "value": "1901-2276"
   *     } ]
   *   }
   */
  public TitleInstance resolve(ContentItemSchema citation) {
    // log.debug("TitleInstanceResolverService::resolve(${citation})");
    TitleInstance result = null;

    List<TitleInstance> candidate_list = classOneMatch(citation.instanceIdentifiers);
    int num_class_one_identifiers = countClassOneIDs(citation.instanceIdentifiers);
    int num_matches = candidate_list.size()
    if ( num_matches > 1 ) {
      log.error("Class one match found multiple titles:: ${candidate_list}");
    }

    // We weren't able to match directly on an identifier for this instance - see if we have an identifier
    // for a sibling instance we can use to narrow down the list.
    if ( num_matches == 0 ) {
      candidate_list = siblingMatch(citation)
      num_matches = candidate_list.size()
      log.debug("siblingMatch for ${citation.title} found ${num_matches} titles");
      if ( num_matches > 1 ) {
        log.error("Sibling match found multiple titles:: ${candidate_list}");
      }
    }

    // If we didn't have a class one identifier AND we weren't able to match anything via
    // a sibling match, try to do a fuzzy match as a last resort
    if ( ( num_matches == 0 ) && ( num_class_one_identifiers == 0 ) ) {
      log.debug("No matches on identifier - try a fuzzy text match on title(${citation.title})");
      // No matches - try a simple title match
      candidate_list = titleMatch(citation.title,MATCH_THRESHOLD);
      num_matches = candidate_list.size()
    }

    if ( candidate_list != null ) {
      switch ( num_matches ) {
        case(0):
          log.debug("No title match, create new title")
          result = createNewTitleInstance(citation)
          createOrLinkSiblings(citation, result.work)
          break;
        case(1):
          log.debug("Exact match. Enrich title.")
          result = candidate_list.get(0)
          checkForEnrichment(result, citation)
          break;
        default:
          log.warn("title matched ${num_matches} records with a threshold >= ${MATCH_THRESHOLD} . Unable to continue. Matching IDs: ${candidate_list.collect { it.id }}. class one identifier count: ${num_class_one_identifiers}");
          // throw new RuntimeException("Title match returned too many items (${num_matches})");
          break;
      }
    }

    return result;
  }  

  /**
   * Return a list of the siblings for this instance. Sometimes vendors identify a title by citing the issn of the print edition.
   * we model the print and electronic as 2 different title instances, linked by a common work. This method looks up/creates any sibling instances
   * by matching the print instance, then looking for a sibling with type "electronic"
   */
  private List<TitleInstance> siblingMatch(ContentItemSchema citation) {
    IdentifierSchema issn_id = citation.siblingInstanceIdentifiers.find { it.namespace == 'issn' } ;
    String issn = issn_id?.value;
    return TitleInstance.executeQuery(SIBLING_MATCH_HQL,[ns:'issn',value:issn,electronic:'electronic']);
  }

  private createOrLinkSiblings(ContentItemSchema citation, Work work) {
    List<TitleInstance> candidate_list = []

    // Lets try and match based on sibling identifiers. 
    // Our first "alternate" matching strategy. Often, KBART files contain the ISSN of the print edition of an electronic work.
    // The line is not suggesting that buying an electronic package includes copies of the physical item, its more a way of saying
    // "The electronic item described by this line relates to the print item identified by X".
    // In the bibframe nomenclature, the print and electronic items are two separate instances. Therefore, creating an electronic
    // identifier with the ID of the print item does not seem sensible. HOWEVER, we would still like to be able to be able to match
    // a title if we know that it is a sibling of a print identifier.
    int num_class_one_identifiers_for_sibling = countClassOneIDs(citation.siblingInstanceIdentifiers)

    Collection<IdentifierSchema> issn_or_isbn_ids = citation.siblingInstanceIdentifiers.findAll { it.namespace.toLowerCase() == 'issn' || it.namespace.toLowerCase() == 'isbn' }
    log.debug("Found list of sibling identifiers: ${issn_or_isbn_ids}")


    if ( issn_or_isbn_ids.size() != 0 ) {
      
      issn_or_isbn_ids.each { id ->
        PackageContentImpl sibling_citation = new PackageContentImpl()
        bindData (sibling_citation, [
          "title": citation.title,
          "instanceMedium": "print",
          "instanceMedia": (id.namespace.toLowerCase() == 'issn') ? "journal" : "book",
          "instanceIdentifiers": [
            [
              "namespace": id.namespace.toLowerCase(),
              "value": id?.value
            ]
          ]
        ])

        if (id.namespace.toLowerCase() == 'isbn') {
          bindData (sibling_citation, [
            "dateMonographPublished": citation.dateMonographPublishedPrint
          ])
        }

        candidate_list = classOneMatch(sibling_citation.instanceIdentifiers)
        switch ( candidate_list.size() ) {
          case 0:
            log.debug("Create sibling print instance for identifier ${id.value}")
            createNewTitleInstance(sibling_citation, work)
            break
          case 1:
            TitleInstance ti = candidate_list.get(0)
            if ( ti.work == null ) {
              log.debug("Located existing print instance for identifier ${id.value} that was not linked. Linking it to work ${work.id}");
              // Link the located title instance to the work
              ti.work = work
              ti.save(flush:true, failOnError:true)
            }
            else {
              log.debug("We found an existing print instance that has a linked work already. Need to check ${ti.work} is equal to ${work}.")
              // Validate that the work we detected is the same as the one we have - otherwise there is bad
              // data flying around.
            }
            break;
          default:
            // Problem
            log.warn("Detected multiple records for sibling instance match")
            break;
        }
      }
    }
  }

  private TitleInstance createNewTitleInstance(final ContentItemSchema citation, Work work = null) {

    TitleInstance result = null

    // With the introduction of fuzzy title matching, we are relaxing this constraint and
    // will expect to enrich titles without identifiers when we next see a record. BUT
    // this needs elaboration and experimentation.
    //
    // boolean title_is_valid =  ( ( citation.title?.length() > 0 ) && ( citation.instanceIdentifiers.size() > 0 ) )
    // 
    boolean title_is_valid = ( ( citation.title != null ) &&
                               ( citation.title.length() > 0 ) )

    // Validate
    if ( title_is_valid == true ) {

      if ( work == null ) {
        work = new Work(title:citation.title).save(flush:true, failOnError:true)
      }

      // Print or Electronic
      def medium = citation.instanceMedium?.trim()

      // Journal or Book etc
      def resource_type = citation.instanceMedia?.trim()
      result = new TitleInstance(
        name: citation.title,

        dateMonographPublished: citation.dateMonographPublished,
        firstAuthor: citation.firstAuthor,
        firstEditor: citation.firstEditor,
        monographEdition: citation.monographEdition,
        monographVolume: citation.monographVolume,

        work: work
      )
      
      if ((medium?.length() ?: 0) > 0) {
        result.subTypeFromString = medium
      }
      
      if ((resource_type?.length() ?: 0) > 0) {
        result.typeFromString = resource_type
      }

      result.save(flush:true, failOnError:true)

      // Iterate over all idenifiers in the citation and add them to the title record. We manually create the identifier occurrence 
      // records rather than using the groovy collection, but it makes little difference.
      citation.instanceIdentifiers.each { id ->
        
        def id_lookup = lookupOrCreateIdentifier(id.value, id.namespace)
        
        def io_record = new IdentifierOccurrence(
          title: result, 
          identifier: id_lookup)
        
        io_record.setStatusFromString(APPROVED)
        io_record.save(flush:true, failOnError:true)
      }
    }
    else { 
      log.error("Create title failed validation checks - insufficient data to create a title record");
      // We will return null, which means no title
      // throw new RuntimeException("Insufficient detail to create title instance record");
    }

    // Refresh the newly minted title so we have access to all the related objects (eg Identifiers)
    result.refresh()
    result
  }

  /**
   * Check to see if the citation has properties that we really want to pull through to
   * the DB. In particular, for the case where we have created a stub title record without
   * an identifier, we will need to add identifiers to that record when we see a record that
   * suggests identifiers for that title match.
   */ 
  private void checkForEnrichment(TitleInstance title, ContentItemSchema citation) {
    return
  }

  /**
   * Given an identifier in a citation { value:'1234-5678', namespace:'isbn' } lookup or create an identifier in the DB to represent that info
   */
  private Identifier lookupOrCreateIdentifier(final String value, final String namespace) {
    Identifier result = null;
    def identifier_lookup = Identifier.executeQuery('select id from Identifier as id where id.value = :value and id.ns.value = :ns',[value:value, ns:namespace]);
    switch(identifier_lookup.size() ) {
      case 0:
        IdentifierNamespace ns = lookupOrCreateIdentifierNamespace(namespace);
        result = new Identifier(ns:ns, value:value).save(flush:true, failOnError:true);
        break;
      case 1:
        result = identifier_lookup.get(0);
        break;
      default:
        throw new RuntimeException("Matched multiple identifiers for ${id}");
        break;
    }
    return result;
  }

  private IdentifierNamespace lookupOrCreateIdentifierNamespace(final String ns) {
    IdentifierNamespace.findOrCreateByValue(ns).save(flush:true, failOnError:true)
  }

  /**
   * Attempt a fuzzy match on the title
   */
  private List<TitleInstance> titleMatch(String title, float threshold) {
    return titleMatch(title, threshold, 'electronic');
  }

  private List<TitleInstance> titleMatch(final String title, final float threshold, final String subtype) {

    List<TitleInstance> result = new ArrayList<TitleInstance>()
    TitleInstance.withSession { session ->
      try {
        result = TitleInstance.executeQuery(TEXT_MATCH_TITLE_HQL,[qrytitle: (title),threshold: (threshold), subtype:subtype], [max:20])
      }
      catch ( Exception e ) {
        log.error("Problem attempting to run HQL Query ${TEXT_MATCH_TITLE_HQL} on string ${title} with threshold ${threshold}",e)
      }
    }
 
    return result
  }

  private int countClassOneIDs(final Iterable<IdentifierSchema> identifiers) {
    identifiers?.findAll( { IdentifierSchema id -> class_one_namespaces?.contains( id.namespace.toLowerCase() ) })?.size() ?: 0
  }

  /**
   * Being passed a map of namespace, value pair maps, attempt to locate any title instances with class 1 identifiers (ISSN, ISBN, DOI)
   */
  private List<TitleInstance> classOneMatch(final Iterable<IdentifierSchema> identifiers) {
    // We want to build a list of all the title instance records in the system that match the identifiers. Hopefully this will return 0 or 1 records.
    // If it returns more than 1 then we are in a sticky situation, and cleverness is needed.
    final List<TitleInstance> result = new ArrayList<TitleInstance>()

    int num_class_one_identifiers = 0;

    identifiers.each { IdentifierSchema id ->
      if ( class_one_namespaces?.contains(id.namespace.toLowerCase()) ) {

        num_class_one_identifiers++;

        // Look up each identifier
        // log.debug("${id} - try class one match");
        final List<Identifier> id_matches = Identifier.executeQuery('select id from Identifier as id where id.value = :value and id.ns.value = :ns',[value:id.value, ns:id.namespace], [max:2])

        assert ( id_matches.size() <= 1 )

        // For each matched (It should only ever be 1)
        id_matches.each { matched_id ->
          // For each occurrence where the STATUS is APPROVED
          matched_id.occurrences.each { io ->
            if ( io.status?.value == APPROVED ) {
              if ( result.contains(io.title) ) {
                // We have already seen this title, so don't add it again
              }
              else {
                // log.debug("Adding title ${io.title.id} ${io.title.title} to matches for ${matched_id}");
                result << io.title
              }
            }
            else {
              throw new RuntimeException("Match on non-approved");
            }
          }
        }
      }
      else {
        // log.debug("Identifier ${id} not from a class one namespace");
      }
    }

    // log.debug("At end of classOneMatch, resut contains ${result.size()} titles");
    return result;
  }
}
