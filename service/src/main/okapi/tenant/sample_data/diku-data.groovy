import org.olf.kb.RemoteKB
log.info "Running specific diku tenant data file"
// A special record for packages which are really defined locally - this is an exceptional situation
RemoteKB local_kb = RemoteKB.findByName('LOCAL') ?: new RemoteKB(
    name:'LOCAL',
    rectype: RemoteKB.RECTYPE_PACKAGE,
    active:Boolean.TRUE,
    supportsHarvesting:false,
    activationEnabled:false
).save(failOnError:true)

RemoteKB gokb_test = RemoteKB.findByName('GOKb_TEST') ?: new RemoteKB(
    name:'GOKb_TEST',
    type:'org.olf.kb.adapters.GOKbOAIAdapter',
    uri:'http://gokbt.gbv.de/gokb/oai/index/packages',
    fullPrefix:'gokb',
    rectype: RemoteKB.RECTYPE_PACKAGE,
    active:Boolean.TRUE,
    supportsHarvesting:true,
    activationEnabled:false
).save(flush:true, failOnError:true)

//RemoteKB ebsco_live = RemoteKB.findByName('EBSCO_LIVE') ?: new RemoteKB(
//    name:'EBSCO_LIVE',
//    type:'org.olf.kb.adapters.EbscoKBAdapter',
//    uri:'https://sandbox.ebsco.io',
//    principal:'YOUR_CLIENT_ID',
//    credentials:'YOUR_API_KEY',
//    rectype: RemoteKB.RECTYPE_PACKAGE,
//    active:Boolean.FALSE,
//    supportsHarvesting:false,
//    activationSupported:true,
//    activationEnabled:false
//).save(flush:true, failOnError:true)
