package org.olf

import org.olf.kb.Platform
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import com.k_int.okapi.OkapiHeaders

import grails.gorm.multitenancy.Tenants
import grails.testing.mixin.integration.Integration
import spock.lang.*

@Integration
@Stepwise
class PlatformSpec extends BaseSpec {

  final static Logger log = LoggerFactory.getLogger(PlatformSpec.class)

  void "Test Platform creation" ( final String platformUrl, final String name ) {
    final String tenantid = currentTenant
    when:
      setHeaders((OkapiHeaders.TENANT): tenantid)
    
      def platform = null;
      Tenants.withId(tenantid + '_olf_erm') {
        platform = Platform.resolve(platformUrl)
      }

    then:
      platform.name == name

    where:
      platformUrl                             || name
      'http://content.apa.org/journals/str'   || 'content.apa.org'

  }
}

