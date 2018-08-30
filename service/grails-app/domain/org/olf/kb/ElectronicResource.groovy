package org.olf.kb

import grails.gorm.MultiTenant
import javax.persistence.Transient
import org.olf.erm.AgreementLineItem

/**
 * an ElectronicResource - Superclass of PlatformTitleInstance and Package
 * and a kind of synonym for "Buyable thing"
 *
 * N.B. THIS CLASS MAPS TO A VIEW NOT A TABLE - IT IS HERE TO SUPPORT THE eRESOURCE wireframe. TAKE CARE!
 *
 */
public class ElectronicResource implements MultiTenant<ElectronicResource> {
 
  String type
  Pkg pkg
  PlatformTitleInstance pti
  String name
  Platform plat

  static mapping = {
      table 'all_electronic_resources'
    version false
       type column:'type'
        pkg column:'pkg_id'
        pti column:'pti_id'
       name column:'name'
       plat column:'plat_fk'
  }

}
