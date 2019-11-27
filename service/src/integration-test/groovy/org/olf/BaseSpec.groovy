package org.olf

import com.k_int.okapi.OkapiHeaders
import com.k_int.web.toolkit.testing.HttpSpec

import spock.util.concurrent.PollingConditions

abstract class BaseSpec extends HttpSpec {
  def setupSpec() {
    httpClientConfig = {
      client.clientCustomizer { HttpURLConnection conn ->
        conn.connectTimeout = 2000
        conn.readTimeout = 10000
      }
    }
    addDefaultHeaders(
      (OkapiHeaders.TENANT): 'http_tests',
      (OkapiHeaders.USER_ID): 'http_test_user'
    )
  }
  
  Map<String, String> getAllHeaders() {
    specDefaultHeaders + headersOverride
  }
  
  String getCurrentTenant() {
    allHeaders?.get(OkapiHeaders.TENANT)
  }

  void 'Ensure test tenant' () {

    // Max time to wait is 10 seconds
    def conditions = new PollingConditions(timeout: 10)
    when: 'Create the tenant'
      def resp = doPost('/_/tenant', {
        parameters ([["key": "loadReference", "value": true]])
      })

    then: 'Response obtained'
      resp != null

    and: 'Refdata added'

      List list
      // Wait for the refdata to be loaded.
      conditions.eventually {
        (list = doGet('/erm/refdata')).size() > 0
      }
  }
  
  def cleanupSpecWithSpring() {
    Map resp = doDelete('/_/tenant', null)
  }
}
